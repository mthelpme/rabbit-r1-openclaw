package com.r1immersive

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * R1 Immersive — forces the status bar hidden in every app, with swipe-from-top to reveal it
 * transiently. It hooks android.app.Activity (a framework class) so a single "System Framework"
 * scope covers all apps. On Android 14 this succeeds where `policy_control immersive.status` fails
 * (that only hides the bar on the launcher).
 *
 * Reveal: BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE — swipe down from the top edge shows the bar
 * briefly, then it auto-hides again.
 */
class ImmersiveHook : IXposedHookZygoteInit, IXposedHookLoadPackage {

    private fun hideBars(activity: Activity) {
        try {
            val window = activity.window ?: return
            if (Build.VERSION.SDK_INT >= 30) {
                val c: WindowInsetsController = window.insetsController ?: return
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            }
        } catch (t: Throwable) {
            XposedBridge.log("R1Immersive hideBars error: $t")
        }
    }

    /** Hook Activity's foreground callbacks so the bar is re-asserted whenever an activity shows. */
    private fun install(cl: ClassLoader?) {
        runCatching {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        (p.thisObject as? Activity)?.let { hideBars(it) }
                    }
                })
        }.onFailure { XposedBridge.log("R1Immersive hook onResume failed: $it") }

        runCatching {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onWindowFocusChanged",
                java.lang.Boolean.TYPE,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (p.args.getOrNull(0) == true) (p.thisObject as? Activity)?.let { hideBars(it) }
                    }
                })
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        // Hook the framework Activity class in the zygote → applies to every forked app process.
        install(null)
        XposedBridge.log("R1Immersive: installed (zygote)")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Fallback / per-app path (harmless if the zygote hook already covers this process).
        install(lpparam.classLoader)
    }
}
