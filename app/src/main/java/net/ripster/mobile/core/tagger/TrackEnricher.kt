package net.ripster.mobile.core.tagger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.core.net.RipsterHttp
import okhttp3.Request

/**
 * Дотягивание МЕТАДАННЫХ перед записью тегов — автономно, на телефоне.
 *
 * Зачем: сервис-загрузчик отдаёт ровно то, что было в его выдаче, и часть
 * полей там пустует (жалоба тестера 03.09.2026: в скачанном нет жанра,
 * композитора, номера трека, обложки). У ПК-Ripster для этого есть теггер,
 * который матчит трек по ISRC в чужих каталогах и добирает недостающее.
 * Здесь то же самое, но БЕЗ ПК: модуль самодостаточный, работает с
 * выключенной app.py — иначе мобильный Ripster перестаёт быть коробочным.
 *
 * Источник: публичный Deezer (`track/isrc:<ISRC>`) — ключа не требует,
 * отдаёт жанр альбома, номер трека/диска,총 количество, дату, лейбл, BPM и
 * обложку. Матч по ISRC точный: это не «похожий трек», а тот же самый.
 *
 * Контракт: НИЧЕГО не перетирает. Заполняются только пустые поля исходного
 * [Track] — то, что сервис уже дал, всегда главнее. Любой сбой сети или
 * разбора — возвращаем исходный трек как есть; теги не повод ронять загрузку.
 */
object TrackEnricher {

    private val json = Json { ignoreUnknownKeys = true }
    private const val TIMEOUT_MS = 8_000L

    suspend fun enrich(track: Track): Track {
        val isrc = track.isrc?.trim()?.takeIf { it.length >= 10 } ?: return track
        // Дотягивать нечего — все интересные поля уже на месте.
        if (track.genre != null && track.trackNumber != null &&
            track.trackTotal != null && track.releaseDate != null
        ) return track

        val body = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { get("https://api.deezer.com/track/isrc:$isrc") }.getOrNull()
        } ?: return track

        return runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            if (o["id"] == null) return track
            fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull
            val album = o["album"]?.jsonObject
            val albumId = album?.get("id")?.jsonPrimitive?.contentOrNull

            // Жанр и лейбл лежат не в треке, а в альбоме — отдельный запрос,
            // и только если жанра у нас всё ещё нет.
            var genre = track.genre
            var label = track.label
            var trackTotal = track.trackTotal
            if ((genre == null || label == null || trackTotal == null) && albumId != null) {
                val ab = withTimeoutOrNull(TIMEOUT_MS) {
                    runCatching { get("https://api.deezer.com/album/$albumId") }.getOrNull()
                }
                if (ab != null) runCatching {
                    val a = json.parseToJsonElement(ab).jsonObject
                    genre = genre ?: a["genres"]?.jsonObject?.get("data")?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    label = label ?: a["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    trackTotal = trackTotal ?: a["nb_tracks"]?.jsonPrimitive?.intOrNull
                }
            }

            track.copy(
                genre = genre,
                label = label,
                trackTotal = trackTotal,
                trackNumber = track.trackNumber ?: int("track_position"),
                discNumber = track.discNumber ?: int("disk_number"),
                releaseDate = track.releaseDate ?: str("release_date"),
                year = track.year ?: str("release_date")?.take(4)?.toIntOrNull(),
                artworkUrl = track.artworkUrl
                    ?: album?.get("cover_xl")?.jsonPrimitive?.contentOrNull
                    ?: album?.get("cover_big")?.jsonPrimitive?.contentOrNull,
                albumTitle = track.albumTitle
                    ?: album?.get("title")?.jsonPrimitive?.contentOrNull,
            )
        }.getOrDefault(track)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        RipsterHttp.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }
}
