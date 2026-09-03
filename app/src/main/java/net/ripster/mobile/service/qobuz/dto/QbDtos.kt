package net.ripster.mobile.service.qobuz.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QbArtist(val id: Long = 0, val name: String = "")

@Serializable
data class QbImage(
    val large: String? = null,
    val small: String? = null,
    val thumbnail: String? = null,
    @SerialName("back") val back: String? = null,
)

@Serializable
data class QbAlbum(
    val id: String = "",
    val title: String = "",
    val artist: QbArtist = QbArtist(),
    val image: QbImage = QbImage(),
    @SerialName("tracks_count") val tracksCount: Int? = null,
    @SerialName("released_at") val releasedAt: Long? = null,
    val upc: String? = null,
    @SerialName("maximum_bit_depth") val maxBitDepth: Int? = null,
    @SerialName("maximum_sampling_rate") val maxSampleRate: Double? = null,
    // Полные метаданные для тегов файла: Qobuz отдаёт их прямо в ответе.
    val genre: QbNamed? = null,
    val label: QbNamed? = null,
    val copyright: String? = null,
    @SerialName("media_count") val mediaCount: Int? = null,
    @SerialName("release_date_original") val releaseDateOriginal: String? = null,
)

/** Вложенный объект вида `{"id":…,"name":"Dance"}` — жанр, лейбл. */
@Serializable
data class QbNamed(val id: Long? = null, val name: String = "")

@Serializable
data class QbTrack(
    val id: Long = 0,
    val title: String = "",
    val duration: Long = 0,
    val isrc: String? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("media_number") val mediaNumber: Int? = null,
    val performer: QbArtist = QbArtist(),
    val album: QbAlbum? = null,
    val composer: QbNamed? = null,
    val copyright: String? = null,
    @SerialName("maximum_bit_depth") val maxBitDepth: Int? = null,
    @SerialName("maximum_sampling_rate") val maxSampleRate: Double? = null,
    val streamable: Boolean = true,
)

@Serializable
data class QbTrackItems(val items: List<QbTrack> = emptyList(), val total: Int = 0)

@Serializable
data class QbSearch(val tracks: QbTrackItems = QbTrackItems())

@Serializable
data class QbAlbumFull(
    val id: String = "",
    val title: String = "",
    val artist: QbArtist = QbArtist(),
    val image: QbImage = QbImage(),
    val upc: String? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
    @SerialName("released_at") val releasedAt: Long? = null,
    val genre: QbNamed? = null,
    val label: QbNamed? = null,
    val copyright: String? = null,
    @SerialName("media_count") val mediaCount: Int? = null,
    @SerialName("release_date_original") val releaseDateOriginal: String? = null,
    val tracks: QbTrackItems = QbTrackItems(),
)

@Serializable
data class QbArtistAlbum(
    val id: String = "",
    val title: String = "",
    val artist: QbArtist = QbArtist(),
    val image: QbImage = QbImage(),
    @SerialName("released_at") val releasedAt: Long? = null,
    @SerialName("release_date_original") val releaseDateOriginal: String? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
    @SerialName("release_type") val releaseType: String? = null,
    val url: String? = null,
)

@Serializable
data class QbArtistAlbums(val items: List<QbArtistAlbum> = emptyList())

@Serializable
data class QbArtistFull(
    val id: Long = 0,
    val name: String = "",
    val image: QbImage = QbImage(),
    val albums: QbArtistAlbums = QbArtistAlbums(),
)

@Serializable
data class QbLogin(
    @SerialName("user_auth_token") val userAuthToken: String = "",
    val user: QbUser = QbUser(),
)

@Serializable
data class QbUser(val id: Long = 0, val email: String = "")

@Serializable
data class QbFileUrl(
    val url: String = "",
    @SerialName("format_id") val formatId: Int = 0,
    @SerialName("mime_type") val mimeType: String = "",
    @SerialName("bit_depth") val bitDepth: Int? = null,
    @SerialName("sampling_rate") val samplingRate: Double? = null,
    val sample: Boolean = false,
)
