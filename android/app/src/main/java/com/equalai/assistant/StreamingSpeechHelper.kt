package com.equalai.assistant

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Dedicated microphone / speech-to-text engine.
 *
 *  - OFFLINE: the phone's on-device recognizer when it exists (Android 12+), live partial results.
 *    If that model is missing or fails, it falls back to the standard recognizer asking for offline use.
 *  - ONLINE: the standard (cloud) recognizer.
 *
 * It never forces a language: it uses the language of the phone's voice input, which is the one whose
 * offline pack is usually installed. Must be used from the main thread.
 */
class StreamingSpeechHelper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** True only when the phone has a working, validated internet connection. */
    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Starts one listening session.
     * @param online true = cloud recognizer, false = offline (on-device first).
     * @param onFail gets the SpeechRecognizer error code and a readable message.
     */
    fun begin(
        online: Boolean,
        partialResults: Boolean,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onFail: (Int, String) -> Unit,
        onListeningState: (Boolean) -> Unit
    ) {
        if (!isAvailable() && !isOnDeviceAvailable()) {
            onFail(-1, "Speech recognition is not available on this phone")
            return
        }
        stop()
        val useOnDevice = !online && isOnDeviceAvailable()
        run(useOnDevice, online, partialResults, onPartial, onResult, onFail, onListeningState)
    }

    private fun run(
        useOnDevice: Boolean,
        online: Boolean,
        partialResults: Boolean,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onFail: (Int, String) -> Unit,
        onListeningState: (Boolean) -> Unit
    ) {
        val rec = try {
            if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            onFail(-1, "Could not start the microphone: ${e.localizedMessage}")
            return
        }
        recognizer = rec

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                onListeningState(true)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                isListening = false
                onListeningState(false)
                // The on-device model failed (not just "heard nothing"): retry once with the standard recognizer.
                if (useOnDevice && error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                ) {
                    try { rec.destroy() } catch (e: Exception) { }
                    if (recognizer === rec) recognizer = null
                    run(false, online, partialResults, onPartial, onResult, onFail, onListeningState)
                    return
                }
                onFail(error, errorMessage(error, online))
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                onListeningState(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onResult(text.orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !online)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            isListening = false
            onListeningState(false)
            onFail(-1, "Could not start listening: ${e.localizedMessage}")
        }
    }

    fun errorMessage(error: Int, online: Boolean): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Internet too slow"
        SpeechRecognizer.ERROR_NETWORK ->
            if (online) "Network error. Check your internet or switch on Offline mode"
            else "Offline speech model not installed. Open Setup and tap \"Download offline speech\""
        12, 13 -> "Offline speech model for this language is not installed. Open Setup and tap \"Download offline speech\""
        SpeechRecognizer.ERROR_SERVER -> "Speech server error. Try again in a moment"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        else -> "Speech recognition error ($error)"
    }

    /**
     * Is the offline speech pack for the phone's language installed? Answers on the main thread.
     * Needs Android 13+ to be exact; older versions can only say whether an on-device recognizer exists.
     */
    fun checkOfflineSupport(onResult: (installed: Boolean, text: String) -> Unit) {
        val lang = Locale.getDefault().toLanguageTag()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val has = isOnDeviceAvailable() || isAvailable()
            onResult(has, if (has) "Voice input is available. This Android version can't report the offline pack." else "No speech recognizer on this phone.")
            return
        }
        try {
            val r = if (isOnDeviceAvailable()) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            }
            r.checkRecognitionSupport(i, ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    val base = lang.substringBefore('-')
                    val installed = recognitionSupport.installedOnDeviceLanguages.any { it.startsWith(base, ignoreCase = true) }
                    val pending = recognitionSupport.pendingOnDeviceLanguages.any { it.startsWith(base, ignoreCase = true) }
                    onResult(
                        installed,
                        when {
                            installed -> "Installed for $lang. Offline voice is ready."
                            pending -> "Downloading for $lang…"
                            else -> "Not installed for $lang."
                        }
                    )
                    try { r.destroy() } catch (e: Exception) { }
                }

                override fun onError(error: Int) {
                    onResult(false, "Could not check the offline pack (error $error).")
                    try { r.destroy() } catch (e: Exception) { }
                }
            })
        } catch (e: Throwable) {
            onResult(false, "Could not check the offline pack: ${e.localizedMessage}")
        }
    }

    /**
     * Asks Android to download the offline speech model (Android 13+). On older versions, or if that is
     * refused, opens the phone's voice-input settings so the pack can be downloaded by hand.
     */
    fun downloadOfflineModel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isOnDeviceAvailable()) {
            try {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                }
                r.triggerModelDownload(i)
                return "Downloading the offline speech model. Keep Wi-Fi on for a minute."
            } catch (e: Throwable) {
                // fall through to the settings page
            }
        }
        return try {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "In Voice input, open Offline speech recognition and download your language."
        } catch (e: Exception) {
            "Open Settings, Languages & input, Voice input, Offline speech recognition."
        }
    }

    fun stop() {
        recognizer?.apply {
            try { cancel() } catch (e: Exception) { }
            try { destroy() } catch (e: Exception) { }
        }
        recognizer = null
        isListening = false
    }
}
