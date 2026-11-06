package io.hazaar.lapdog.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's voice: Android text-to-speech, so a pilot with goggles on can be told
 * what to do (CalibrationGuide). Started with LapService; works with the screen off.
 * Speaks on the navigation-guidance stream, ducking music while it talks. With no
 * usable voice it plays the tone lap sound instead, and [status] says why so the UI
 * can offer to install one.
 */
object Voice {
    enum class Status { STARTING, READY, NO_VOICE_DATA, NO_ENGINE }

    private const val TAG = "Voice"
    private var tts: TextToSpeech? = null
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private val ids = AtomicInteger()
    private val speaking = AtomicInteger() // utterances queued and not done

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Synchronized
    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        tts = TextToSpeech(app) { result ->
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "no TTS engine ($result)")
                _status.value = Status.NO_ENGINE
                return@TextToSpeech
            }
            val t = tts ?: return@TextToSpeech
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = finished()
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = finished()
                override fun onError(id: String?, code: Int) = finished()
            })
            _status.value = when (pickLanguage(t)) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> Status.NO_VOICE_DATA
                else -> Status.READY
            }
            Log.i(TAG, "TTS ${_status.value} (${t.defaultEngine}, ${t.voice?.locale})")
        }
    }

    /** The phone's language if the engine has it, else English. */
    private fun pickLanguage(t: TextToSpeech): Int {
        val mine = t.setLanguage(Locale.getDefault())
        if (mine >= TextToSpeech.LANG_AVAILABLE) return mine
        return t.setLanguage(Locale.US)
    }

    /**
     * Says [text]. [interrupt] drops anything still queued first (a new step), else it
     * waits its turn (pass counts after the round line).
     */
    fun say(text: String, interrupt: Boolean = false) {
        val t = tts
        if (t == null || _status.value != Status.READY) {
            LapSounds.play(LapSound.TONE) // no voice: at least a cue
            return
        }
        if (speaking.getAndIncrement() == 0) focus?.let { audio?.requestAudioFocus(it) }
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (interrupt) speaking.set(1)
        t.speak(text, mode, null, "lapdog-${ids.incrementAndGet()}")
    }

    private fun finished() {
        if (speaking.decrementAndGet() <= 0) {
            speaking.set(0)
            focus?.let { audio?.abandonAudioFocusRequest(it) }
        }
    }

    /** Opens the system screen that downloads voice data for the engine. */
    fun installIntent(): Intent =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
