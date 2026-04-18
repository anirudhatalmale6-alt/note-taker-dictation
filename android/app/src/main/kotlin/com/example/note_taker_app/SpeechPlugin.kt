package com.example.note_taker_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel

class SpeechPlugin : FlutterPlugin, ActivityAware, EventChannel.StreamHandler {

    companion object {
        private const val TAG = "SpeechPlugin"
        private const val CHANNEL = "speech_continuous"
        // Restart delay kept minimal for seamless experience
        private const val RESTART_DELAY_MS = 100L
        // How long to keep audio muted after starting recognition (covers the beep window)
        private const val MUTE_DURATION_MS = 1500L
    }

    private lateinit var channel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var activity: Activity? = null
    private var shouldContinue = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Store original volumes to restore later
    private var savedMusicVol = -1
    private var savedNotifVol = -1
    private var savedRingVol = -1
    private var savedSystemVol = -1
    private var isMuted = false

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = EventChannel(binding.binaryMessenger, CHANNEL)
        channel.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setStreamHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        shouldContinue = false
        destroyRecognizer()
        restoreVolume()
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    // --- EventChannel StreamHandler ---

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
        eventSink = sink
        shouldContinue = true
        activity?.runOnUiThread {
            createAndStart()
        }
    }

    override fun onCancel(arguments: Any?) {
        shouldContinue = false
        activity?.runOnUiThread {
            speechRecognizer?.stopListening()
            destroyRecognizer()
            restoreVolume()
        }
        eventSink = null
    }

    // --- Core speech logic ---

    private fun createAndStart() {
        val act = activity ?: return
        if (!shouldContinue) return

        // Destroy previous instance cleanly
        destroyRecognizer()

        // Mute all audio streams BEFORE creating recognizer to suppress beep
        muteAllStreams()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(act).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                    // Schedule unmute after beep window passes
                    scheduleUnmute()
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech started")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "End of speech")
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "Error: $error")
                    // Error 6 = SPEECH_TIMEOUT, 7 = NO_MATCH — these are normal "silence" events
                    // Error 8 = RECOGNIZER_BUSY — need to recreate
                    // Error 9 = INSUFFICIENT_PERMISSIONS
                    if (shouldContinue) {
                        val delay = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RESTART_DELAY_MS
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 500L
                            else -> 300L
                        }
                        mainHandler.postDelayed({
                            if (shouldContinue) {
                                activity?.runOnUiThread { createAndStart() }
                            }
                        }, delay)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    Log.d(TAG, "Final results: $texts")
                    if (!texts.isNullOrEmpty()) {
                        mainHandler.post {
                            eventSink?.success(mapOf("type" to "final", "text" to texts[0]))
                        }
                    }
                    // Restart immediately for continuous listening
                    if (shouldContinue) {
                        mainHandler.postDelayed({
                            if (shouldContinue) {
                                activity?.runOnUiThread { createAndStart() }
                            }
                        }, RESTART_DELAY_MS)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!texts.isNullOrEmpty()) {
                        mainHandler.post {
                            eventSink?.success(mapOf("type" to "partial", "text" to texts[0]))
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            // Build recognition intent
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_DEFAULTS_ON_ERROR, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Long silence tolerance (8 seconds)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }

            recognizer.startListening(intent)
            Log.d(TAG, "Started listening")
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying recognizer: ${e.message}")
        }
        speechRecognizer = null
    }

    // --- Audio muting for beep suppression ---

    private fun muteAllStreams() {
        val act = activity ?: return
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (!isMuted) {
            // Save current volumes
            savedMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedNotifVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            savedRingVol = am.getStreamVolume(AudioManager.STREAM_RING)
            savedSystemVol = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
        }

        // Mute all streams that could produce the beep
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        isMuted = true
    }

    private fun scheduleUnmute() {
        mainHandler.postDelayed({
            restoreVolume()
        }, MUTE_DURATION_MS)
    }

    private fun restoreVolume() {
        if (!isMuted) return
        val act = activity ?: return
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            if (savedMusicVol >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0)
            if (savedNotifVol >= 0) am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVol, 0)
            if (savedRingVol >= 0) am.setStreamVolume(AudioManager.STREAM_RING, savedRingVol, 0)
            if (savedSystemVol >= 0) am.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring volume: ${e.message}")
        }
        isMuted = false
    }
}
