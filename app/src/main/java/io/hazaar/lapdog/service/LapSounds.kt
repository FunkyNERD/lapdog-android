package io.hazaar.lapdog.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import io.hazaar.lapdog.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the phone plays on every gate pass. The sounds are made by tools/gen-sounds.py. */
enum class LapSound(val label: String, val hint: String, @RawRes val res: Int?) {
    BARK("Bark", "A real dog, the default", R.raw.lap_bark),
    TONE("Tone", "Clean 880 Hz beep", R.raw.lap_tone),
    DING("Ding", "Bright struck note", R.raw.lap_ding),
    BELL("Bell", "Small bell, rings on", R.raw.lap_bell),
    CHIRP("Chirp", "Quick rising whistle", R.raw.lap_chirp),
    DOUBLE_BEEP("Double beep", "Like a stopwatch lap button", R.raw.lap_double_beep),
    OFF("Off", "Silent: laps still count", null),
}

/**
 * The lap sound: the pilot's choice (kept on the phone, not the timer) and a SoundPool
 * to play it. Played from LapRepository as each new lap frame arrives, so it works with
 * the screen off while LapService runs. Plays on the media volume.
 */
object LapSounds {
    private const val KEY = "lap_sound"
    private var prefs: SharedPreferences? = null
    private var pool: SoundPool? = null
    private val ids = HashMap<LapSound, Int>()

    private val _sound = MutableStateFlow(LapSound.BARK)
    val sound: StateFlow<LapSound> = _sound

    @Synchronized
    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        val p = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs = p
        _sound.value = p.getString(KEY, null)?.let { name -> LapSound.entries.firstOrNull { it.name == name } } ?: LapSound.BARK
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build().also { sp ->
            LapSound.entries.forEach { s -> s.res?.let { ids[s] = sp.load(app, it, 1) } }
        }
    }

    /** Makes [s] the lap sound (saved on the phone). Previews use [play]. */
    fun select(s: LapSound) {
        _sound.value = s
        prefs?.edit()?.putString(KEY, s.name)?.apply()
    }

    /** Plays [s] (the chosen sound by default); nothing for Off or before [init]. */
    fun play(s: LapSound = _sound.value) {
        val id = ids[s] ?: return
        pool?.play(id, 1f, 1f, 1, 0, 1f)
    }
}
