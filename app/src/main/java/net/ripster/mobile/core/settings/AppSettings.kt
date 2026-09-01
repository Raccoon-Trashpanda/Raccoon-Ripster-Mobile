package net.ripster.mobile.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.ripster.mobile.core.model.QualityTier

/**
 * Несекретные настройки приложения. Секреты — в [CredentialStore], не здесь.
 *
 * Держим снимок в [state] (`StateFlow`), чтобы Compose перерисовывался на
 * изменение, и одновременно пишем в `SharedPreferences` для персиста.
 * Ключи по возможности повторяют имена из десктопного `config.yaml` —
 * это упростит синк настроек по сопряжению.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ripster_settings", Context.MODE_PRIVATE)

    data class Snapshot(
        val uiLang: String = "ru",
        // Neon — утверждённый скин (design/design-system-neon.md): near-black +
        // радиальные подсветки + маджента-акцент со свечением. Это дефолт.
        val theme: String = "Neon",
        val density: String = "Normal",
        /** Предпочтение качества по Wi-Fi (лучшее сначала). */
        val qualityWifi: List<String> = QualityTier.DEFAULT_PREFERENCE,
        /** Предпочтение качества по мобильной сети — как в Apple Music, отдельно. */
        val qualityCellular: List<String> = listOf("mp3_320", "aac_256", "mp3_128"),
        val wifiOnly: Boolean = true,
        val parallelDownloads: Int = 2,
        /** SAF-дерево, куда писать готовые файлы. Пусто → ещё не выбрано, файл остаётся в кэше. */
        val downloadTreeUri: String = "",
        /** Шаблон пути/имени внутри дерева. */
        val nameTemplate: String = net.ripster.mobile.core.storage.NameTemplate.DEFAULT,
        /** Вид экрана «Сейчас играет»: "reference" (крупная обложка, квадратные
         *  органы, панели треклист/текст/спектр/эквалайзер) или "studio" (строгая
         *  дизайн-система). По умолчанию reference — это основной вид. */
        val playerStyle: String = "reference",
        /** Пресет спектрограммы: ripster / sox / magma / mono / cool. */
        val spectrumStyle: String = "ripster",
        /**
         * Качество ПО СЕРВИСУ: serviceId → id тира ([QualityTier]). Пусто —
         * берётся глобальное предпочтение сети. Ripster учитывает это в
         * `DownloadWorker` (см. [qualityPrefFor]).
         */
        val perServiceQuality: Map<String, String> = emptyMap(),
        /** Адаптивные цвета — подстраивать фон плеера под палитру обложки. */
        val adaptiveColors: Boolean = true,
        // ── запоминаемый выбор на экране поиска ────────────────────────────
        /** id сервисов, СНЯТЫХ галочкой в поиске (пусто = искать во всех). */
        val searchServicesOff: Set<String> = emptySet(),
        /** Фильтр типа: 0 всё · 1 альбомы · 2 синглы/EP · 3 треки. */
        val searchType: Int = 0,
        /** Сортировка результатов: false релевантность · true новизна. */
        val searchSortNew: Boolean = false,
        /** Фильтр года ("" = любой). */
        val searchYear: String = "",
        /** Первый запуск пройден (язык/тема/шрифт/папка выбраны). */
        val onboardingDone: Boolean = false,
        /** Нативный аудиодвижок (Oboe) для локального lossless вместо ExoPlayer. */
        val nativeEngine: Boolean = false,
    ) {
        /** Масштаб шрифта от «плотности» — применяется глобально к fontScale,
         *  БЕЗ смены dp (мишени касания не едут). Именно это делает выбор
         *  размера шрифта в настройках реально ощутимым. */
        val fontScale: Float
            get() = when (density) {
                "Compact" -> 0.90f
                "Large" -> 1.18f
                else -> 1.0f
            }

        /** Предпочтение для текущего типа сети. */
        fun qualityFor(onWifi: Boolean): List<String> = if (onWifi) qualityWifi else qualityCellular

        /**
         * Итоговый список предпочтения для конкретного сервиса: если задан
         * per-service тир — он первым, дальше сетевой список (движок всё равно
         * деградирует к доступному, если точного тира у сервиса нет).
         */
        fun qualityPrefFor(serviceId: String, onWifi: Boolean): List<String> {
            val base = qualityFor(onWifi)
            val forced = perServiceQuality[serviceId]?.takeIf { it.isNotBlank() }
            return if (forced != null) listOf(forced) + base.filter { it != forced } else base
        }
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private fun load() = Snapshot(
        uiLang = prefs.getString(K_LANG, "ru") ?: "ru",
        theme = prefs.getString(K_THEME, "Neon") ?: "Neon",
        density = prefs.getString(K_DENSITY, "Normal") ?: "Normal",
        qualityWifi = prefs.csv(K_QWIFI) ?: QualityTier.DEFAULT_PREFERENCE,
        qualityCellular = prefs.csv(K_QCELL) ?: listOf("mp3_320", "aac_256", "mp3_128"),
        wifiOnly = prefs.getBoolean(K_WIFI_ONLY, true),
        parallelDownloads = prefs.getInt(K_PARALLEL, 2).coerceIn(1, 6),
        downloadTreeUri = prefs.getString(K_TREE, "") ?: "",
        nameTemplate = prefs.getString(K_TEMPLATE, null)
            ?: net.ripster.mobile.core.storage.NameTemplate.DEFAULT,
        playerStyle = prefs.getString(K_PLAYER_STYLE, "reference") ?: "reference",
        spectrumStyle = prefs.getString(K_SPEC_STYLE, "ripster") ?: "ripster",
        perServiceQuality = (prefs.getString(K_PER_SVC_Q, "") ?: "")
            .split(';').mapNotNull { pair ->
                val kv = pair.split('=', limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0] to kv[1] else null
            }.toMap(),
        adaptiveColors = prefs.getBoolean(K_ADAPTIVE, true),
        searchServicesOff = (prefs.getString(K_SRCH_OFF, "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        searchType = prefs.getInt(K_SRCH_TYPE, 0).coerceIn(0, 3),
        searchSortNew = prefs.getBoolean(K_SRCH_SORT, false),
        searchYear = prefs.getString(K_SRCH_YEAR, "") ?: "",
        onboardingDone = prefs.getBoolean(K_ONBOARDING, false),
        nativeEngine = prefs.getBoolean(K_NATIVE_ENGINE, false),
    )

    fun update(block: (Snapshot) -> Snapshot) {
        val next = block(_state.value)
        prefs.edit()
            .putString(K_LANG, next.uiLang)
            .putString(K_THEME, next.theme)
            .putString(K_DENSITY, next.density)
            .putString(K_QWIFI, next.qualityWifi.joinToString(","))
            .putString(K_QCELL, next.qualityCellular.joinToString(","))
            .putBoolean(K_WIFI_ONLY, next.wifiOnly)
            .putInt(K_PARALLEL, next.parallelDownloads.coerceIn(1, 6))
            .putString(K_TREE, next.downloadTreeUri)
            .putString(K_TEMPLATE, next.nameTemplate)
            .putString(K_PLAYER_STYLE, next.playerStyle)
            .putString(K_SPEC_STYLE, next.spectrumStyle)
            .putString(K_PER_SVC_Q, next.perServiceQuality.entries.joinToString(";") { "${it.key}=${it.value}" })
            .putBoolean(K_ADAPTIVE, next.adaptiveColors)
            .putString(K_SRCH_OFF, next.searchServicesOff.joinToString(","))
            .putInt(K_SRCH_TYPE, next.searchType.coerceIn(0, 3))
            .putBoolean(K_SRCH_SORT, next.searchSortNew)
            .putString(K_SRCH_YEAR, next.searchYear)
            .putBoolean(K_ONBOARDING, next.onboardingDone)
            .putBoolean(K_NATIVE_ENGINE, next.nativeEngine)
            .apply()
        _state.value = next
    }

    private fun SharedPreferences.csv(key: String): List<String>? =
        getString(key, null)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    private companion object {
        const val K_LANG = "language"
        const val K_THEME = "theme"
        const val K_DENSITY = "density"
        const val K_QWIFI = "quality-preference-wifi"
        const val K_QCELL = "quality-preference-cellular"
        const val K_WIFI_ONLY = "download-wifi-only"
        const val K_PARALLEL = "parallel-downloads"
        const val K_TREE = "download-tree-uri"
        const val K_TEMPLATE = "name-template"
        const val K_PLAYER_STYLE = "player-style"
        const val K_SPEC_STYLE = "spectrum-style"
        const val K_PER_SVC_Q = "quality-per-service"
        const val K_ADAPTIVE = "adaptive-colors"
        const val K_SRCH_OFF = "search-services-off"
        const val K_SRCH_TYPE = "search-type"
        const val K_SRCH_SORT = "search-sort-new"
        const val K_SRCH_YEAR = "search-year"
        const val K_ONBOARDING = "onboarding-done"
        const val K_NATIVE_ENGINE = "native-engine"
    }
}
