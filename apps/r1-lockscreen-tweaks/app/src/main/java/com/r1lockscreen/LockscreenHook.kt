package com.r1lockscreen

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * R1 Lockscreen Tweaks — centres the keyguard clock/date, hides the lock icon, and hides the
 * bouncer's emergency button.
 *
 * Why Xposed and not the RRO in apps/r1-lockscreen-overlay: none of these are resource values.
 * The clock is pinned by `android:layout_alignParentStart="true"` — a literal in
 * keyguard_clock_switch.xml — and the emergency button needs `visibility="gone"` in
 * keyguard_emergency_carrier_area.xml. An overlay would have to replace whole layouts and
 * hard-code SystemUI's numeric resource IDs, which shift on every OTA and fail as a lockscreen
 * that won't inflate. A hook fails soft instead.
 *
 * Safety rules this file follows:
 *  - Scope is com.android.systemui ONLY (see res/values/arrays.xml).
 *  - Every hook target is verified to be DECLARED on its class before hooking. Hooking an
 *    inherited method (e.g. setVisibility, which LockIconView does not declare) would resolve to
 *    android.view.View and apply to every view in SystemUI.
 *  - Each tweak is independently guarded; one failing never affects the others, and no exception
 *    is allowed to escape into keyguard code. A broken keyguard means a device you cannot unlock.
 *
 * Hook targets were read off the device's own SystemUI.apk (dexdump), not assumed:
 *   KeyguardClockSwitch      declares onFinishInflate, updateStatusArea; fields mSmallClockFrame, mStatusArea
 *   LockIconView             declares updateIcon, updateColorAndBackgroundVisibility
 *   EmergencyButton          declares onFinishInflate
 *   EmergencyButtonController declares updateEmergencyCallButton
 */
class LockscreenHook : IXposedHookLoadPackage {

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val TAG = "R1Lockscreen"

        // Each tweak is independent — set any to false to drop just that one.
        const val CENTER_CLOCK = true
        const val HIDE_LOCK_ICON = true

        /**
         * Hides the bouncer's emergency-call button.
         *
         * Note this removes the ability to dial emergency services from the lock screen without
         * unlocking the device. That is a real capability on a phone with a SIM; set this to
         * false to keep it.
         */
        const val HIDE_EMERGENCY_BUTTON = true

        /**
         * Lockscreen carrier text. The name shown there comes from the telephony `siminfo` DB
         * column `carrier_name`, which the framework re-derives from the SIM every time the phone
         * process starts — `name_source` protects `display_name`, not `carrier_name`. Writing the
         * DB therefore cannot hold: it is reverted on the next boot. Rewriting it at the point
         * SystemUI is handed the string is deterministic instead.
         *
         * Set CARRIER_TEXT to "" to leave the real carrier alone.
         */
        const val SET_CARRIER_TEXT = true

        /**
         * Read at runtime from `Settings.Global.r1_carrier_label`, which already existed on this
         * device — so the label is changeable with a single `settings put` and no rebuild:
         *
         *     adb shell settings put global r1_carrier_label "openclaw"
         *
         * CARRIER_TEXT is only the fallback when that setting is unset.
         */
        const val CARRIER_SETTING = "r1_carrier_label"
        const val CARRIER_TEXT = "openclaw"
    }

    private fun log(m: String) = XposedBridge.log("$TAG: $m")

    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.packageName != SYSTEMUI) return
        val cl = lp.classLoader
        if (CENTER_CLOCK) guard("centerClock") { centerClock(cl) }
        if (HIDE_LOCK_ICON) guard("hideLockIcon") { hideLockIcon(cl) }
        if (HIDE_EMERGENCY_BUTTON) guard("hideEmergencyButton") { hideEmergencyButton(cl) }
        if (SET_CARRIER_TEXT) guard("setCarrierText") { setCarrierText(cl) }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { log("$what install failed: $t") }
    }

    // ---------------------------------------------------------------------------------------
    // Hooking only what a class actually declares.
    // ---------------------------------------------------------------------------------------

    /**
     * Hook [method] on [className] only if that class DECLARES it. XposedHelpers.findAndHookMethod
     * walks up the hierarchy, so hooking an inherited method here would hook it for every
     * subclass in the process — hooking View.setVisibility would blank all of SystemUI.
     */
    private fun hookDeclared(
        className: String,
        cl: ClassLoader,
        method: String,
        vararg paramTypes: Class<*>,
        callback: XC_MethodHook,
    ): Boolean {
        val clazz = XposedHelpers.findClassIfExists(className, cl) ?: run {
            log("class absent: $className"); return false
        }
        val declared: Method = clazz.declaredMethods.firstOrNull {
            it.name == method && it.parameterTypes.size == paramTypes.size &&
                it.parameterTypes.zip(paramTypes).all { (a, b) -> a == b }
        } ?: run {
            log("$className does not declare $method(${paramTypes.size} args) — skipped"); return false
        }
        XposedBridge.hookMethod(declared, callback)
        return true
    }

    /** Like [hookDeclared] but matches on name alone, for methods whose parameter types we do not
     *  want to name (R8 renames the parameter classes). Still declared-only. */
    private fun hookDeclaredByName(
        className: String,
        cl: ClassLoader,
        method: String,
        callback: XC_MethodHook,
    ): Boolean {
        val clazz = XposedHelpers.findClassIfExists(className, cl) ?: run {
            log("class absent: $className"); return false
        }
        val matches = clazz.declaredMethods.filter { it.name == method }
        if (matches.isEmpty()) { log("$className does not declare $method — skipped"); return false }
        matches.forEach { XposedBridge.hookMethod(it, callback) }
        return true
    }

    private fun after(body: (XC_MethodHook.MethodHookParam) -> Unit) = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            try { body(p) } catch (t: Throwable) { log("hook body: $t") }
        }
    }

    // ---------------------------------------------------------------------------------------
    // 1. Centre the clock and the date/status area.
    // ---------------------------------------------------------------------------------------

    private fun centerClock(cl: ClassLoader) {
        val recentre = after { p ->
            val root = p.thisObject as? ViewGroup ?: return@after
            centreChild(root, "mSmallClockFrame", "lockscreen_clock_view")
            centreChild(root, "mStatusArea", "keyguard_status_area")
            root.requestLayout()
        }
        // onFinishInflate covers first layout; updateStatusArea re-runs when the date/smartspace
        // changes and can reset the params, so re-assert there too.
        hookDeclared("com.android.keyguard.KeyguardClockSwitch", cl, "onFinishInflate", callback = recentre)
        hookDeclared("com.android.keyguard.KeyguardClockSwitch", cl, "updateStatusArea",
            java.lang.Boolean.TYPE, callback = recentre)
    }

    /** Prefer the private field (exact), fall back to id lookup if the field was renamed. */
    private fun centreChild(root: ViewGroup, field: String, idName: String) {
        val v = (runCatching { XposedHelpers.getObjectField(root, field) as? View }.getOrNull())
            ?: root.findViewById(
                root.context.resources.getIdentifier(idName, "id", SYSTEMUI)
            )
            ?: return

        (v.layoutParams as? RelativeLayout.LayoutParams)?.let { lp ->
            lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
            lp.removeRule(RelativeLayout.ALIGN_PARENT_LEFT)
            lp.addRule(RelativeLayout.CENTER_HORIZONTAL)
            v.layoutParams = lp
        }
        // keyguard_clock_switch.xml sets paddingStart=@dimen/clock_padding_start; that indent
        // would push a centred view off-centre.
        v.setPadding(0, v.paddingTop, 0, v.paddingBottom)
        centreTree(v)
    }

    /**
     * The date is not a direct child of keyguard_status_area — that view is match_parent and
     * holds an <include> (the smartspace), so centring only the container does nothing and the
     * text stays start-aligned. Walk the subtree instead.
     */
    private fun centreTree(v: View, depth: Int = 0) {
        if (depth > 6) return
        (v as? android.widget.TextView)?.gravity = Gravity.CENTER_HORIZONTAL
        (v as? LinearLayout)?.gravity = Gravity.CENTER_HORIZONTAL
        (v as? ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) centreTree(g.getChildAt(i), depth + 1)
        }
    }

    // ---------------------------------------------------------------------------------------
    // 2. Hide the lock icon.
    // ---------------------------------------------------------------------------------------

    private fun hideLockIcon(cl: ClassLoader) {
        val hide = after { p -> (p.thisObject as? View)?.visibility = View.GONE }
        // LockIconView declares neither onFinishInflate nor setVisibility, so re-assert on the
        // two methods it does declare — both run whenever the icon's state changes.
        val a = hookDeclared("com.android.keyguard.LockIconView", cl, "updateIcon", callback = hide)
        val b = hookDeclared("com.android.keyguard.LockIconView", cl,
            "updateColorAndBackgroundVisibility", callback = hide)

        // The view's own methods are not enough: LockIconViewController re-asserts visibility from
        // keyguard/biometric state and wins. Its updateVisibility method carries an R8 suffix on
        // this build (updateVisibility$3), so match by prefix rather than exact name.
        var c = false
        XposedHelpers.findClassIfExists("com.android.keyguard.LockIconViewController", cl)
            ?.declaredMethods
            ?.filter { it.name.startsWith("updateVisibility") && it.parameterTypes.isEmpty() }
            ?.forEach { m ->
                XposedBridge.hookMethod(m, after { p ->
                    val v = runCatching { XposedHelpers.getObjectField(p.thisObject, "mView") as? View }
                        .getOrNull()
                    v?.visibility = View.GONE
                })
                c = true
                log("lock icon: hooked controller ${m.name}")
            }
        if (!a && !b && !c) log("lock icon: no hookable method found — icon left visible")
    }

    // ---------------------------------------------------------------------------------------
    // 3. Hide the bouncer's emergency button.
    // ---------------------------------------------------------------------------------------

    private fun hideEmergencyButton(cl: ClassLoader) {
        hookDeclared("com.android.keyguard.EmergencyButton", cl, "onFinishInflate",
            callback = after { p -> (p.thisObject as? View)?.let { forceHidden(it, "onFinishInflate") } })

        // EmergencyButtonController.updateEmergencyCallButton() re-shows the button from telephony
        // state and wins over anything set at inflation, so override its decision after it runs.
        // The controller's view field is R8-renamed on this build, so find it by type rather than
        // by the AOSP name (mView).
        hookDeclared("com.android.keyguard.EmergencyButtonController", cl, "updateEmergencyCallButton",
            callback = after { p ->
                val v = findViewField(p.thisObject)
                if (v == null) log("emergency: no View field on controller") else forceHidden(v, "controller")
            })
    }

    /** First View-typed declared field on [owner], searched up the class hierarchy. */
    private fun findViewField(owner: Any): View? {
        var c: Class<*>? = owner.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!View::class.java.isAssignableFrom(f.type)) continue
                runCatching {
                    f.isAccessible = true
                    (f.get(owner) as? View)?.let { return it }
                }
            }
            c = c.superclass
        }
        return null
    }

    /**
     * GONE alone is not enough — the controller sets visibility back to VISIBLE on state changes
     * and may run after our hook. Collapsing the height (the layout hard-codes 48dp) survives
     * that, and clearing enabled/clickable makes sure an invisible button can never be pressed.
     */
    private fun forceHidden(v: View, why: String) {
        v.visibility = View.GONE
        v.layoutParams?.let { lp -> lp.height = 0; v.layoutParams = lp }
        v.isEnabled = false
        v.isClickable = false
        v.alpha = 0f
        log("emergency button hidden ($why)")
    }

    // ---------------------------------------------------------------------------------------
    // 4. Lockscreen carrier text.
    // ---------------------------------------------------------------------------------------

    /** Views we've already attached the carrier watcher to. */
    private val carrierWatched = WeakHashMap<TextView, Boolean>()

    /** Settings value if present, else the compiled-in fallback. Re-read on every change so a
     *  `settings put` takes effect without restarting SystemUI. */
    private fun carrierLabel(ctx: android.content.Context): String {
        val v = runCatching {
            android.provider.Settings.Global.getString(ctx.contentResolver, CARRIER_SETTING)
        }.getOrNull()
        return if (!v.isNullOrEmpty()) v else CARRIER_TEXT
    }

    private fun setCarrierText(cl: ClassLoader) {
        if (CARRIER_TEXT.isEmpty()) return
        // CarrierTextManager.postToCallback is declared but never fires on this build — verified
        // with logging, no callback was ever observed. Rather than keep chasing whichever producer
        // is live, anchor on the view: whatever sets the text, put it back. A TextWatcher is
        // self-correcting and does not care which code path won.
        val ok = hookDeclaredByName("com.android.keyguard.CarrierTextController", cl, "onViewAttached",
            after { p ->
                val tv = findViewField(p.thisObject) as? TextView ?: run {
                    log("carrier: controller has no TextView field"); return@after
                }
                if (carrierWatched.put(tv, true) != null) return@after
                tv.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                    override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                    override fun afterTextChanged(e: Editable?) {
                        // Re-entrant by design: setText fires this again, but the second pass
                        // compares equal and stops.
                        val want = carrierLabel(tv.context)
                        if (e?.toString() != want) tv.text = want
                    }
                })
                val want = carrierLabel(tv.context)
                tv.text = want
                log("carrier: watcher installed, text now $want")
            })
        if (!ok) log("carrier: CarrierTextController.onViewAttached not hooked")
    }

}
