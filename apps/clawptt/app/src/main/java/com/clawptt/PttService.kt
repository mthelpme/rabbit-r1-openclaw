package com.clawptt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.widget.RemoteViews
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Orchestrates a PTT turn: hold=record, release=STT -> chat -> TTS. Drives PttActivity (the
 * lock-screen panel) via ACTION_STATE broadcasts. After speaking, the panel stays in a READ
 * phase (so you can finish reading) until Close, a new turn, or a timeout. Stop-speaking mutes
 * audio without dismissing.
 */
class PttService : Service() {

    companion object {
        const val ACTION_PTT_DOWN = "com.clawptt.PTT_DOWN"
        const val ACTION_PTT_UP = "com.clawptt.PTT_UP"
        const val ACTION_CANCEL = "com.clawptt.CANCEL"
        const val ACTION_MUTE = "com.clawptt.MUTE"
        const val ACTION_SPEAK = "com.clawptt.SPEAK"
        const val ACTION_SPEAK_TEXT = "com.clawptt.SPEAK_TEXT"  // replay a specific answer from the chat page
        const val ACTION_DISMISS = "com.clawptt.DISMISS"
        const val ACTION_STOP = "com.clawptt.STOP"
        const val ACTION_STATE = "com.clawptt.STATE"
        const val ACTION_ATTACH = "com.clawptt.ATTACH"   // conversation page is foreground
        const val ACTION_DETACH = "com.clawptt.DETACH"
        const val ACTION_RESET = "com.clawptt.RESET"     // New chat: next hold is a fresh one-off panel
        const val ACTION_SEND_TEXT = "com.clawptt.SEND_TEXT"  // typed message from the chat page
        private const val CHANNEL = "clawptt"
        private const val CHANNEL_REPLY = "clawptt_replies"
        private const val NOTIF_ID = 42
        private const val NOTIF_REPLY_ID = 43
        private const val HOLD_THRESHOLD_MS = 250L    // must hold this long before listening starts
        private const val READ_TIMEOUT_MS = 30000L    // how long the reply stays up to read
    }

    private enum class State { IDLE, LISTENING, THINKING, SPEAKING }
    @Volatile private var state = State.IDLE
    @Volatile private var armed = false
    // When the dedicated conversation page is foreground it renders turns inline, so we suppress
    // the pop-up panel and its read-timeout auto-dismiss. Broadcasts still fire either way.
    @Volatile private var attached = false
    // A per-turn generation id. Incremented at turn start and on cancel; a worker whose captured
    // gen no longer matches turnId has been orphaned (canceled) and must not send/persist/speak.
    @Volatile private var turnId = 0
    @Volatile private var pendingGen = 0            // gen for an in-flight SpeechRecognizer result
    @Volatile private var turnsThisSession = 0      // >=1 means a follow-up should open the chat page
    @Volatile private var lastReply = ""
    @Volatile private var lastTranscript = ""
    @Volatile private var streamAccum = ""
    // Last live UI state, replayed to a page that attaches mid-turn (it misses the first broadcast).
    @Volatile private var lastPhase = ""
    @Volatile private var lastStatus = ""
    @Volatile private var lastBody = ""
    @Volatile private var cachedAudio: java.io.File? = null
    private fun isNetworkTts() = cfg.ttsMode == Config.TtsMode.KOKORO ||
        cfg.ttsMode == Config.TtsMode.ELEVENLABS || cfg.ttsMode == Config.TtsMode.VENICE
    private val startTurnRunnable = Runnable { startTurn() }
    private val dismissRunnable = Runnable { done() }

    private lateinit var cfg: Config
    private lateinit var stt: SttEngine
    private lateinit var srStt: SpeechRecognizerStt
    private lateinit var tts: TtsEngine
    private lateinit var gateway: GatewayClient
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val usingRecognizer get() = cfg.sttMode == Config.SttMode.SPEECH_RECOGNIZER

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        stt = SttEngine(this, cfg)
        gateway = GatewayClient(cfg)
        tts = TtsEngine(this, cfg).also { it.init() }
        tts.onDone = { main.post { if (state == State.SPEAKING) enterRead() } }
        srStt = SpeechRecognizerStt(this).apply {
            onResult = { t -> main.post { handleTranscript(t, pendingGen) } }
            onPartial = { p -> main.post { if (state == State.LISTENING) ui(State.LISTENING, "LISTENING", "🎙️  Listening…", "“$p”") } }
            onError = { e -> main.post { flash("⚠️  $e") } }
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        when (intent?.action) {
            ACTION_PTT_DOWN -> onDown()
            ACTION_PTT_UP -> onUp()
            ACTION_CANCEL -> onCancel()
            ACTION_MUTE -> onMute()
            ACTION_SPEAK -> onSpeak()
            ACTION_SPEAK_TEXT -> onSpeakText(intent.getStringExtra("text").orEmpty())
            ACTION_DISMISS -> done()
            ACTION_ATTACH -> { attached = true; replayState() }
            ACTION_DETACH -> attached = false
            ACTION_RESET -> turnsThisSession = 0
            ACTION_SEND_TEXT -> onTextMessage(intent.getStringExtra("text"))
            ACTION_STOP -> { done(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_STICKY
    }

    /** A typed message from the chat page: skip STT and run it as a turn. */
    private fun onTextMessage(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isBlank()) return
        if (state != State.IDLE) { flash("…finish the current reply first"); return }
        if (!cfg.isConfigured) { flash("⚠️  Set gateway URL + token in ClawPTT"); return }
        turnId++                       // new turn; handleTranscript broadcasts THINKING + streams
        handleTranscript(t, turnId)
    }

    private fun onDown() {
        if (state == State.SPEAKING) { onMute(); return }   // stop speech but keep the text up
        if (state != State.IDLE || armed) return
        if (!cfg.isConfigured) { flash("⚠️  Set gateway URL + token in ClawPTT"); return }
        armed = true
        main.postDelayed(startTurnRunnable, HOLD_THRESHOLD_MS)
    }

    /** Fires only if still held past the threshold (a real hold, not a tap). */
    private fun startTurn() {
        armed = false
        turnId++                          // begin a new turn; orphans any prior in-flight worker
        main.removeCallbacks(dismissRunnable)
        try {
            if (usingRecognizer) srStt.start() else stt.start()
            // Conversation page renders inline. Otherwise: first turn -> quick pop-up panel;
            // a follow-up (continuing the conversation) opens the full chat page.
            if (!attached) { if (turnsThisSession >= 1) openChat() else showPanel() }
            ui(State.LISTENING, "LISTENING", "🎙️  Listening…", "")
        } catch (e: Exception) { flash("⚠️  ${e.message}") }
    }

    private fun onUp() {
        if (armed) { armed = false; main.removeCallbacks(startTurnRunnable); return }  // tap
        if (state != State.LISTENING) return
        val gen = turnId
        ui(State.THINKING, "THINKING", "⏳  Thinking…", "")
        if (usingRecognizer) {
            pendingGen = gen            // the async onResult will carry this gen into handleTranscript
            srStt.stop()
        } else {
            worker.execute {
                val t0 = System.currentTimeMillis()
                try {
                    val t = stt.stopAndTranscribe()
                    android.util.Log.i("ClawPTT", "STT ok in ${System.currentTimeMillis() - t0}ms text=\"${t.take(80)}\"")
                    main.post { handleTranscript(t, gen) }
                } catch (e: Exception) {
                    android.util.Log.w("ClawPTT", "STT failed in ${System.currentTimeMillis() - t0}ms: ${e.javaClass.simpleName}: ${e.message}")
                    if (gen == turnId) main.post { flash("⚠️  ${e.message}") }  // ignore errors from a canceled turn
                }
            }
        }
    }

    private fun handleTranscript(t: String, gen: Int) {
        if (gen != turnId) return                          // turn was canceled during transcription
        if (t.isBlank()) { flash("…didn't catch that"); return }
        lastTranscript = t; streamAccum = ""; cachedAudio = null
        ui(State.THINKING, "THINKING", "⏳  Thinking…", "“$t”")
        worker.execute {
            try {
                if (gen != turnId) return@execute             // canceled before the request started
                val sb = StringBuilder()
                val sentenceBuf = StringBuilder()
                val chunkTts = cfg.speakAloud && cfg.ttsMode == Config.TtsMode.SYSTEM && tts.isReady()
                var announced = false
                var lastDisplay = 0L

                val full = gateway.chatStream(t, onStatus = { tool ->
                    // Agent is running a tool before it starts writing — surface it on the Thinking screen.
                    main.post { if (gen == turnId && state == State.THINKING) ui(State.THINKING, "THINKING", "🔧  $tool", "“$t”") }
                }) { delta ->
                    if (gen != turnId) return@chatStream      // canceled mid-stream: stop updating/speaking
                    sb.append(delta); streamAccum = sb.toString()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!announced || now - lastDisplay > 120) {
                        lastDisplay = now
                        val disp = stripMarkdown(sb.toString())
                        if (!announced) { announced = true; main.post { answerBroadcast("SPEAKING", disp) } }
                        else main.post { streamText(disp) }
                    }
                    if (chunkTts) {
                        sentenceBuf.append(delta)
                        for (s in drainSentences(sentenceBuf)) tts.enqueue(stripMarkdown(s), false)
                    }
                }

                if (gen != turnId) return@execute             // canceled just as the stream finished
                val fullClean = stripMarkdown(full)
                lastReply = fullClean
                History.add(this, t, fullClean)
                Conversation.add(this, cfg.sessionKey, Conversation.USER, t)
                Conversation.add(this, cfg.sessionKey, Conversation.ASSISTANT, fullClean)
                // Async: if you fired this and walked away (chat page not open), ping you with the result.
                if (cfg.notifyReplies && !attached) main.post { notifyReply(t, fullClean) }
                turnsThisSession++                            // a completed turn -> follow-ups open the chat page
                main.post { if (announced) streamText(fullClean) else answerBroadcast("SPEAKING", fullClean) }

                when {
                    !cfg.speakAloud -> {
                        main.post { enterRead() }                                          // text now; audio in background
                        if (cfg.preGenAudio && isNetworkTts()) cachedAudio = tts.fetchToCache(fullClean)
                    }
                    chunkTts -> tts.enqueue(stripMarkdown(sentenceBuf.toString()), true)  // final -> onDone -> read
                    else -> tts.speak(fullClean)                                          // Eleven / Venice / Kokoro / not-ready
                }
            } catch (e: Exception) {
                val canceled = gen != turnId ||
                    (e is java.io.IOException && e.message?.contains("canceled", true) == true)
                if (!canceled) android.util.Log.w("ClawPTT", "turn failed: ${e.javaClass.simpleName}: ${e.message}")
                // On cancel, whoever canceled (Stop -> read, Cancel -> dismiss) already set the UI.
                if (!canceled) main.post { flash("⚠️  ${e.message}") }
            }
        }
    }

    private fun streamText(text: String) {
        state = State.SPEAKING
        remember("SPEAKING", "", text)
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra("phase", "SPEAKING").putExtra("body", text)
                .putExtra("recap", lastTranscript).putExtra("done", false)
        )
    }

    /** Pulls complete sentences out of the buffer, leaving any trailing partial. */
    private fun drainSentences(buf: StringBuilder): List<String> {
        val out = ArrayList<String>()
        while (true) {
            var end = -1
            for (i in 0 until buf.length) {
                val c = buf[i]
                if ((c == '.' || c == '!' || c == '?' || c == '\n') && i + 1 < buf.length &&
                    (buf[i + 1] == ' ' || buf[i + 1] == '\n')) { end = i + 1; break }
            }
            if (end == -1) break
            val s = buf.substring(0, end).trim()
            buf.delete(0, end)
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** Stop audio (and any in-flight stream) but keep the reply on screen. */
    private fun onMute() {
        gateway.cancel()
        tts.stop()
        if (streamAccum.isNotBlank()) lastReply = stripMarkdown(streamAccum)
        enterRead()
    }

    /** Speak (or replay) the current answer on demand — works even if auto-speak is off. */
    private fun onSpeak() {
        if (lastReply.isBlank()) return
        tts.stop()
        answerBroadcast("SPEAKING", lastReply)
        val c = cachedAudio
        worker.execute {
            try { if (c != null && c.exists()) tts.playCached(c) else tts.speak(lastReply) }
            catch (e: Exception) { android.util.Log.w("ClawPTT", "replay failed: ${e.javaClass.simpleName}: ${e.message}"); main.post { flash("⚠️  ${e.message}") } }
        }
    }

    /** Replay a specific answer (long-press → Play again on the chat page). Audio only, no state change. */
    private fun onSpeakText(text: String) {
        if (text.isBlank()) return
        tts.stop()
        worker.execute {
            try { tts.speak(text) }
            catch (e: Exception) { android.util.Log.w("ClawPTT", "playback failed: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    private fun enterRead() {
        answerBroadcast("READ", lastReply)
        main.removeCallbacks(dismissRunnable)
        if (!attached) main.postDelayed(dismissRunnable, READ_TIMEOUT_MS)  // page stays; nothing to dismiss
    }

    private fun answerBroadcast(phase: String, answer: String) {
        state = if (phase == "SPEAKING") State.SPEAKING else State.IDLE
        remember(phase, "", answer)
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra("phase", phase).putExtra("body", answer)
                .putExtra("recap", lastTranscript).putExtra("done", false)
        )
        updateNotif()
    }

    private fun onCancel() {
        if (armed) { armed = false; main.removeCallbacks(startTurnRunnable) }
        turnId++                        // orphan the in-flight turn: late transcript/stream is discarded
        when (state) {
            // LISTENING: safe to hard-stop capture. THINKING: don't touch file-STT mid-transcribe
            // (avoids a recognizer.close() race) — the gen guard drops its result; just cancel any
            // recognizer + gateway call. SPEAKING: cancel the stream and stop audio.
            State.LISTENING -> if (usingRecognizer) srStt.cancel() else stt.cancel()
            State.THINKING -> { if (usingRecognizer) srStt.cancel(); gateway.cancel() }
            State.SPEAKING -> { gateway.cancel(); tts.stop() }
            else -> {}
        }
        done()
    }

    /** Opens the persistent chat page (follow-up turns / hand-off). Same background-start path as showPanel. */
    private fun openChat() {
        runCatching {
            startActivity(
                Intent(this, ConversationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    // ---- UI (PttActivity) ----
    private fun showPanel() {
        runCatching {
            startActivity(
                Intent(this, PttActivity::class.java)
                    .putExtra("status", "🎙️  Listening…").putExtra("phase", "LISTENING")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun ui(s: State, phase: String, status: String, body: String) {
        state = s
        remember(phase, status, body)
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra("phase", phase).putExtra("status", status).putExtra("body", body).putExtra("done", false)
        )
        updateNotif()
    }

    private fun remember(phase: String, status: String, body: String) {
        lastPhase = phase; lastStatus = status; lastBody = body
    }

    /** Re-emit the current live state so a page that just attached (mid-turn) can sync to it. */
    private fun replayState() {
        if (lastPhase.isEmpty() || lastPhase == "MSG") return
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra("phase", lastPhase).putExtra("status", lastStatus)
                .putExtra("body", lastBody).putExtra("recap", lastTranscript).putExtra("done", false)
        )
    }

    private fun flash(msg: String) {
        state = State.IDLE
        remember("MSG", msg, "")   // so a later attach doesn't replay a stale live phase
        sendBroadcast(
            Intent(ACTION_STATE).setPackage(packageName)
                .putExtra("phase", "MSG").putExtra("status", msg).putExtra("body", "").putExtra("done", false)
        )
        main.postDelayed(dismissRunnable, 1600)
    }

    private fun done() {
        main.removeCallbacks(dismissRunnable)
        state = State.IDLE
        remember("", "", "")   // turn over: nothing to replay to a page that attaches next
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("done", true))
        updateNotif()
    }

    private fun startForegroundCompat() {
        val n = buildNotif(notifTitle())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun notifTitle() = when (state) {
        State.LISTENING -> "openclaw is listening"
        State.THINKING -> "Thinking…"
        State.SPEAKING -> "Speaking"
        else -> "openclaw ready"
    }

    private fun updateNotif() =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(notifTitle()))

    private fun buildNotif(title: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, PttActivity::class.java).putExtra("phase", "IDLE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, PttService::class.java).setAction(ACTION_STOP), flags)
        val rv = RemoteViews(packageName, R.layout.notification_ptt).apply {
            setTextViewText(R.id.n_title, title)
            setOnClickPendingIntent(R.id.n_stop, stop)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ClawPTT", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(   // reply-ready alerts (async): allowed to make noise
            NotificationChannel(CHANNEL_REPLY, "Replies", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /**
     * Post the finished reply as a tappable notification for the async case (fire a task, pocket the
     * R1). Only when the user isn't already watching the chat page. Tap opens the conversation.
     */
    private fun notifyReply(question: String, reply: String) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 2,
            Intent(this, ConversationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val n = Notification.Builder(this, CHANNEL_REPLY)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(question.take(60).ifBlank { "openclaw" })
            .setContentText(reply.take(120))
            .setStyle(Notification.BigTextStyle().bigText(reply.take(1200)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_REPLY_ID, n)
    }

    override fun onDestroy() {
        main.removeCallbacks(dismissRunnable)
        tts.shutdown()
        runCatching { srStt.destroy() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
