package net.ripster.mobile.service.deezer.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

// ---- Публичный api.deezer.com (без ARL) — поиск и метаданные ----

@Serializable
data class DzApiArtist(val id: Long = 0, val name: String = "")

@Serializable
data class DzApiAlbum(
    val id: Long = 0,
    val title: String = "",
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("nb_tracks") val nbTracks: Int? = null,
    @SerialName("record_type") val recordType: String? = null,   // "album" | "single" | "ep" | "compile"
    val artist: DzApiArtist = DzApiArtist(),
    val upc: String? = null,
)

/** api.deezer.com/search/album — {data:[<album>]}. Раньше поиск на мобиле
 *  спрашивал ТОЛЬКО /search/track, поэтому фильтры «Альбомы»/«Синглы/EP» всегда
 *  показывали «ничего нет» (жалоба 03.09.2026 — «bicep по фильтру альбомов
 *  вообще не нашёл ничего»). */
@Serializable
data class DzApiAlbumSearch(val data: List<DzApiAlbum> = emptyList(), val total: Int = 0)

@Serializable
data class DzApiTrack(
    val id: Long = 0,
    val title: String = "",
    val duration: Long = 0,
    val isrc: String? = null,
    @SerialName("track_position") val trackPosition: Int? = null,
    @SerialName("disk_number") val diskNumber: Int? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val artist: DzApiArtist = DzApiArtist(),
    val album: DzApiAlbum? = null,
    val type: String = "track",
)

@Serializable
data class DzApiSearch(val data: List<DzApiTrack> = emptyList(), val total: Int = 0)

@Serializable
data class DzApiAlbumFull(
    val id: Long = 0,
    val title: String = "",
    @SerialName("cover_xl") val coverXl: String? = null,
    @SerialName("nb_tracks") val nbTracks: Int? = null,
    val artist: DzApiArtist = DzApiArtist(),
    val tracks: DzApiTrackList = DzApiTrackList(),
    val upc: String? = null,
)

@Serializable
data class DzApiTrackList(val data: List<DzApiTrack> = emptyList())

// ---- gw-light.php (нужен ARL) ----

@Serializable
data class DzGwEnvelope(
    // `error` бывает и `[]`, и объектом-словарём — берём как есть и смотрим факт.
    val error: JsonElement = JsonNull,
    val results: DzGwResults = DzGwResults(),
) {
    val ok: Boolean get() = error is JsonArray && (error as JsonArray).isEmpty()
    val errorText: String get() = if (ok) "" else error.toString()
}

@Serializable
data class DzGwResults(
    @SerialName("USER") val user: DzGwUser = DzGwUser(),
    @SerialName("checkForm") val checkForm: String = "",
    @SerialName("SESSION_ID") val sessionId: String = "",
    // song.getData
    @SerialName("SNG_ID") val sngId: String = "",
    @SerialName("SNG_TITLE") val sngTitle: String = "",
    @SerialName("ART_NAME") val artName: String = "",
    @SerialName("ALB_TITLE") val albTitle: String = "",
    @SerialName("ALB_PICTURE") val albPicture: String = "",
    @SerialName("TRACK_TOKEN") val trackToken: String = "",
    @SerialName("DURATION") val duration: String = "",
    @SerialName("ISRC") val isrc: String = "",
    @SerialName("TRACK_NUMBER") val trackNumber: String = "",
    @SerialName("DISK_NUMBER") val diskNumber: String = "",
    @SerialName("FILESIZE_FLAC") val filesizeFlac: String = "0",
    @SerialName("FILESIZE_MP3_320") val filesizeMp3_320: String = "0",
    @SerialName("FILESIZE_MP3_128") val filesizeMp3_128: String = "0",
)

@Serializable
data class DzGwUser(
    @SerialName("USER_ID") val userId: Long = 0,
    @SerialName("OPTIONS") val options: DzGwOptions = DzGwOptions(),
)

@Serializable
data class DzGwOptions(
    @SerialName("license_token") val licenseToken: String = "",
    @SerialName("web_hq") val webHq: Boolean = false,
    @SerialName("web_lossless") val webLossless: Boolean = false,
)

// ---- media.deezer.com/v1/get_url ----

@Serializable
data class DzMediaResponse(val data: List<DzMediaData> = emptyList())

@Serializable
data class DzMediaData(val media: List<DzMediaEntry> = emptyList(), val errors: List<DzMediaErr> = emptyList())

@Serializable
data class DzMediaErr(val code: Int = 0, val message: String = "")

@Serializable
data class DzMediaEntry(
    val format: String = "",
    val sources: List<DzMediaSource> = emptyList(),
)

@Serializable
data class DzMediaSource(val url: String = "", val provider: String = "")
