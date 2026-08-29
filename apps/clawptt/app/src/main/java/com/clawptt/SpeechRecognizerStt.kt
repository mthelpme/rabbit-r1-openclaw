package com.clawptt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * STT via Android SpeechRecognizer, bound to whisperIME's on-device Whisper RecognitionService
 * (auto-detected). Push-to-talk: start() on hold, stop() on release -> onResult(transcript).
 * All calls must be on the main thread.
 */
class SpeechRecognizerStt(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null

    var onResult: ((String) -> Unit)? = null
    var onPartial: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start() {
        destroy()
        val comp = findWhisperService()
        recognizer = if (comp != null && Build.VERSION.SDK_INT >= 31)
            SpeechRecognizer.createSpeechRecognizer(ctx, comp)
        else
            SpeechRecognizer.createSpeechRecognizer(ctx)

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                onResult?.invoke(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                if (text.isNotEmpty()) onPartial?.invoke(text)
            }
            override fun onError(error: Int) { onError?.invoke(errText(error)) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Don't let it auto-cut on silence — PTT release decides when we're done.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        recognizer?.startListening(intent)
    }

    /** Release: finalize and transcribe. */
    fun stop() { runCatching { recognizer?.stopListening() } }

    fun cancel() { runCatching { recognizer?.cancel() }; destroy() }

    fun destroy() { runCatching { recognizer?.destroy() }; recognizer = null }

    /** Finds whisperIME's (or any) on-device RecognitionService. */
    private fun findWhisperService(): ComponentName? {
        val services = ctx.packageManager.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE), 0
        )
        val match = services.firstOrNull { it.serviceInfo.packageName.contains("whisper", true) }
            ?: services.firstOrNull()   // fall back to whatever recognition service exists
            ?: return null
        return ComponentName(match.serviceInfo.packageName, match.serviceInfo.name)
    }

    private fun errText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognized"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_CLIENT -> "recognizer client error (is whisperIME installed?)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "mic permission needed"
        else -> "recognizer error $code"
    }
}
