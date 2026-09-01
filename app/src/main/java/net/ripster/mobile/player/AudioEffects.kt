package net.ripster.mobile.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Аудиоэффекты поверх ExoPlayer: эквалайзер (пресеты + ручные полосы),
 * бас-буст, виртуализатор (расширение стерео), усилитель громкости.
 *
 * Всё нативное — `android.media.audiofx.*`, привязка к audio session id
 * плеера. Настройки живут в `SharedPreferences` и применяются при каждой
 * пересборке сессии (смена трека может её пересоздать). На эмуляторе часть
 * эффектов может отсутствовать — каждый вызов в try/catch, панель просто не
 * покажет недоступное.
 */
object AudioEffects {

    data class Bands(
        /** Число полос эквалайзера (0 — недоступен). */
        val count: Int = 0,
        /** Центральные частоты полос, Гц. */
        val centerHz: IntArray = IntArray(0),
        val minMdB: Int = -1500,
        val maxMdB: Int = 1500,
        val presetNames: List<String> = emptyList(),
    )

    data class Config(
        val enabled: Boolean = false,
        /** -1 — ручной режим (полосы), иначе индекс пресета. */
        val preset: Int = -1,
        /** Уровни полос, миллибелы (длина = Bands.count). */
        val levels: List<Int> = emptyList(),
        val bassBoost: Int = 0,        // 0..1000
        val virtualizer: Int = 0,      // 0..1000
        val loudnessMdB: Int = 0,      // 0..2000
    )

    private lateinit var prefs: SharedPreferences
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    private val _bands = MutableStateFlow(Bands())
    val bands: StateFlow<Bands> = _bands.asStateFlow()

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("audio_fx", Context.MODE_PRIVATE)
        _config.value = load()
    }

    /** Привязать эффекты к сессии плеера. Вызывается из [PlaybackService]. */
    fun bind(sessionId: Int) {
        if (sessionId == 0) return
        this.sessionId = sessionId
        release()
        runCatching {
            eq = Equalizer(0, sessionId)
            val e = eq!!
            val n = e.numberOfBands.toInt()
            _bands.value = Bands(
                count = n,
                centerHz = IntArray(n) { e.getCenterFreq(it.toShort()) / 1000 },
                minMdB = e.bandLevelRange[0].toInt(),
                maxMdB = e.bandLevelRange[1].toInt(),
                presetNames = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) },
            )
            // если сохранённых уровней нет — плоско
            if (_config.value.levels.size != n) {
                _config.value = _config.value.copy(levels = List(n) { 0 })
            }
        }
        runCatching { bass = BassBoost(0, sessionId) }
        runCatching { virt = Virtualizer(0, sessionId) }
        runCatching { loud = LoudnessEnhancer(sessionId) }
        apply(_config.value)
    }

    fun release() {
        runCatching { eq?.release() }; eq = null
        runCatching { bass?.release() }; bass = null
        runCatching { virt?.release() }; virt = null
        runCatching { loud?.release() }; loud = null
    }

    // ── изменения из панели ────────────────────────────────────────────────

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    fun setPreset(index: Int) = update { c ->
        val n = _bands.value.count
        val lv = runCatching {
            eq?.usePreset(index.toShort())
            (0 until n).map { eq!!.getBandLevel(it.toShort()).toInt() }
        }.getOrDefault(c.levels)
        c.copy(preset = index, levels = lv)
    }

    fun setBand(band: Int, mdB: Int) = update { c ->
        val lv = c.levels.toMutableList()
        if (band in lv.indices) lv[band] = mdB
        c.copy(preset = -1, levels = lv)
    }

    fun setBassBoost(v: Int) = update { it.copy(bassBoost = v.coerceIn(0, 1000)) }
    fun setVirtualizer(v: Int) = update { it.copy(virtualizer = v.coerceIn(0, 1000)) }
    fun setLoudness(v: Int) = update { it.copy(loudnessMdB = v.coerceIn(0, 2000)) }

    fun resetFlat() = update { it.copy(preset = -1, levels = List(_bands.value.count) { 0 }, bassBoost = 0, virtualizer = 0, loudnessMdB = 0) }

    private fun update(block: (Config) -> Config) {
        val next = block(_config.value)
        _config.value = next
        save(next)
        apply(next)
    }

    // ── применение к нативным эффектам ────────────────────────────────────

    private fun apply(c: Config) {
        val on = c.enabled
        runCatching {
            eq?.let { e ->
                e.enabled = on
                if (on && c.preset >= 0 && c.preset < e.numberOfPresets) {
                    e.usePreset(c.preset.toShort())
                } else if (on) {
                    c.levels.forEachIndexed { i, mdB ->
                        if (i < e.numberOfBands) e.setBandLevel(i.toShort(), mdB.toShort())
                    }
                }
            }
        }
        runCatching {
            bass?.let { it.enabled = on && c.bassBoost > 0; if (it.strengthSupported) it.setStrength(c.bassBoost.toShort()) }
        }
        runCatching {
            virt?.let { it.enabled = on && c.virtualizer > 0; if (it.strengthSupported) it.setStrength(c.virtualizer.toShort()) }
        }
        runCatching {
            loud?.let { it.enabled = on && c.loudnessMdB > 0; it.setTargetGain(c.loudnessMdB) }
        }
    }

    // ── персист ──────────────────────────────────────────────────────────

    private fun load() = Config(
        enabled = prefs.getBoolean("enabled", false),
        preset = prefs.getInt("preset", -1),
        levels = prefs.getString("levels", "")!!.split(',').mapNotNull { it.trim().toIntOrNull() },
        bassBoost = prefs.getInt("bass", 0),
        virtualizer = prefs.getInt("virt", 0),
        loudnessMdB = prefs.getInt("loud", 0),
    )

    private fun save(c: Config) {
        prefs.edit()
            .putBoolean("enabled", c.enabled)
            .putInt("preset", c.preset)
            .putString("levels", c.levels.joinToString(","))
            .putInt("bass", c.bassBoost)
            .putInt("virt", c.virtualizer)
            .putInt("loud", c.loudnessMdB)
            .apply()
    }
}
