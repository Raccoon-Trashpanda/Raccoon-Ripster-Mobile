package net.ripster.mobile.core.service

/**
 * Какому сервису принадлежит ссылка.
 *
 * Перенос ПК-функции `svcFromUrl` из static/js/urlbar_detect.js — владелец
 * 06.09.2026: «автоопределение сервиса, в пк версии уже всё есть, бери и
 * внедряй». Правила те же и в том же порядке; зеркало на бэкенде ПК —
 * service_layer.detect_service.
 *
 * Зачем ОДИН перечень. На ПК их когда-то развели по вкладкам, и у Раскопок он
 * знал шесть сервисов из десяти — клик по находке с Beatport или Яндекса уходил
 * в плеер без имени сервиса. Здесь тот же риск: перечень должен быть один, и
 * он здесь.
 *
 * Пустая строка означает «не знаю», а не «не поддерживается»: короткие ссылки
 * и зеркала домена не называют, и по ним решает уже вызывающий.
 */
object UrlService {

    fun of(raw: String?): String {
        val v = (raw ?: "").lowercase()
        return when {
            "music.apple.com" in v -> "apple"
            "qobuz.com" in v -> "qobuz"
            "deezer.com" in v -> "deezer"
            "deezer.page" in v -> "deezer"
            "tidal.com" in v -> "tidal"
            "soundcloud.com" in v -> "soundcloud"
            // Короткая ссылка SoundCloud: на ПК её разворачивают до /resolve
            // (см. заметку про on.soundcloud.com). Сервис по ней известен сразу.
            "on.soundcloud.com" in v || "snd.sc" in v -> "soundcloud"
            "spotify.com" in v -> "spotify"
            "beatport.com" in v -> "beatport"
            "music.yandex." in v -> "yandex"
            "music.amazon." in v -> "amazon"
            "bbc.co.uk" in v -> "bbc"
            else -> ""
        }
    }

    /** Похоже ли это вообще на ссылку. */
    fun isLink(raw: String?): Boolean {
        val v = (raw ?: "").trim()
        return v.startsWith("http://") || v.startsWith("https://")
    }
}
