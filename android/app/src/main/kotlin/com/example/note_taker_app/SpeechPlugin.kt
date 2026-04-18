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
        private const val RESTART_DELAY_MS = 100L
        // Delay before restoring volume after stopping (covers the stop beep)
        private const val STOP_UNMUTE_DELAY_MS = 2000L
    }

    private lateinit var channel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var activity: Activity? = null
    private var shouldContinue = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Store original volumes to restore only when user stops
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
        restoreVolumeNow()
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
            // Mute ONCE when user taps start — stays muted the entire session
            muteAllStreams()
            createAndStart()
        }
    }

    override fun onCancel(arguments: Any?) {
        shouldContinue = false
        activity?.runOnUiThread {
            // Ensure muted before stopping so the stop beep is silent
            muteAllStreams()
            speechRecognizer?.stopListening()
            destroyRecognizer()
            // Restore volume after a delay to cover the stop beep
            mainHandler.postDelayed({
                restoreVolumeNow()
            }, STOP_UNMUTE_DELAY_MS)
        }
        eventSink = null
    }

    // --- Core speech logic ---

    private fun createAndStart() {
        val act = activity ?: return
        if (!shouldContinue) return

        // Destroy previous instance cleanly
        destroyRecognizer()

        // Ensure still muted before each restart (no unmute between sessions)
        ensureMuted()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(act).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                    // Do NOT unmute here — stay muted the entire session
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

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_DEFAULTS_ON_ERROR, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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
    // Strategy: mute when user starts, stay muted the ENTIRE session, restore only when user stops.
    // This eliminates all beeps between restarts since volume is never restored mid-session.

    private fun muteAllStreams() {
        val act = activity ?: return
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (!isMuted) {
            // Save current volumes only once
            savedMusicVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedNotifVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            savedRingVol = am.getStreamVolume(AudioManager.STREAM_RING)
            savedSystemVol = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
        }

        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        isMuted = true
    }

    private fun ensureMuted() {
        if (!isMuted) {
            muteAllStreams()
            return
        }
        // Already muted — just make sure streams are still at 0
        // (some devices/apps can change volume externally)
        val act = activity ?: return
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
    }

    private fun restoreVolumeNow() {
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
