package net.ripster.mobile.service.soundcloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO для SoundCloud API v2 (`api-v2.soundcloud.com`). Только поля, которые
 * реально используем — остальное игнорируется (`ignoreUnknownKeys = true` в
 * парсере). Это НЕ порт десктопа: там SC качается через Node-библиотеку
 * Lucida в подпроцессе, протокола в нашем коде нет. Здесь — прямая работа с
 * публичным API v2.
 */

@Serializable
data class ScUser(
    val id: Long = 0,
    val username: String = "",
    @SerialName("permalink_url") val permalinkUrl: String = "",
)

@Serializable
data class ScPublisherMeta(
    val isrc: String? = null,
    val artist: String? = null,
    @SerialName("album_title") val albumTitle: String? = null,
)

@Serializable
data class ScFormat(
    val protocol: String = "",          // "progressive" | "hls"
    @SerialName("mime_type") val mimeType: String = "",
)

@Serializable
data class ScTranscoding(
    val url: String = "",
    val preset: String = "",            // "mp3_0_0", "aac_160k", "opus_0_0", "abr_sq", …
    val duration: Long = 0,
    val snipped: Boolean = false,
    val quality: String = "",           // "sq" | "hq"
    val format: ScFormat = ScFormat(),
)

@Serializable
data class ScMedia(
    val transcodings: List<ScTranscoding> = emptyList(),
)

@Serializable
data class ScTrack(
    val id: Long = 0,
    val kind: String = "track",
    val title: String = "",
    val duration: Long = 0,
    val genre: String? = null,
    val isrc: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("permalink_url") val permalinkUrl: String = "",
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("publisher_metadata") val publisherMetadata: ScPublisherMeta? = null,
    val user: ScUser = ScUser(),
    val media: ScMedia = ScMedia(),
    @SerialName("track_authorization") val trackAuthorization: String? = null,
    /** true у контейнерных ответов, где трек пришёл заглушкой с одним id. */
    val streamable: Boolean = true,
) {
    /** Заглушка из плейлиста: есть id, но нет медиа — нужен добор через `/tracks?ids=`. */
    val isStub: Boolean get() = media.transcodings.isEmpty() && title.isEmpty()
}

@Serializable
data class ScPlaylist(
    val id: Long = 0,
    val kind: String = "playlist",
    val title: String = "",
    @SerialName("permalink_url") val permalinkUrl: String = "",
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    val user: ScUser = ScUser(),
    val tracks: List<ScTrack> = emptyList(),
)

@Serializable
data class ScSearch(
    val collection: List<ScTrack> = emptyList(),
    @SerialName("total_results") val totalResults: Int = 0,
    @SerialName("next_href") val nextHref: String? = null,
)

/** Ответ `GET /charts` — станция «топ/тренды по жанру». */
@Serializable
data class ScChart(
    val collection: List<ScChartItem> = emptyList(),
    @SerialName("next_href") val nextHref: String? = null,
)

@Serializable
data class ScChartItem(val track: ScTrack = ScTrack())

/** Ответ `GET <transcoding.url>` — одна строка с настоящим URL потока/плейлиста. */
@Serializable
data class ScStreamUrl(val url: String = "")
