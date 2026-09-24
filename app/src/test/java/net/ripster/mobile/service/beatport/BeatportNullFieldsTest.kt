package net.ripster.mobile.service.beatport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Поиск Beatport не падает, когда поля ответа — явный null.
 *
 * 23.09.2026 живая кампания на эмуляторе (E2E, BUG-2): поиск Beatport не работал
 * вообще, 3/3 запроса, включая заведомо пустой: «Element class JsonNull is not a
 * JsonObject». Beatport отдаёт отсутствующие значения явным null
 * («"sub_genre": null», «"image": null»), а `?.jsonObject` на JsonNull бросает —
 * `?.` защищает только от Kotlin-null. Хост в данных — .invalid, сети нет.
 */
class BeatportNullFieldsTest {
    private val client = BeatportClient(null, null, File("build/tmp/bp-test"))

    private val body = """
        {"tracks": [
          {"id": 1, "name": "Kuumba", "mix_name": "Original Mix",
           "artists": [{"id": 7, "name": "Someone"}],
           "release": {"id": 9, "name": "EP", "image": null},
           "sub_genre": null, "genre": {"name": "Techno (Peak Time / Driving)"},
           "duration": null, "image": null},
          null,
          {"id": 2, "name": "Second", "artists": [], "release": null,
           "sub_genre": {"name": "Raw"}, "genre": null, "duration": {"milliseconds": 360000}}
        ]}
    """.trimIndent()

    @Test
    fun nullFieldsDoNotBreakParsing() {
        val rows = client.rows(body, "tracks")
        assertEquals("null-элемент массива пропускается, а не роняет разбор", 2, rows.size)
        val tracks = rows.map { client.trackOf(it) }
        assertEquals("Kuumba", tracks[0].title.substringBefore(" (").trim())
        assertTrue(tracks[1].title.startsWith("Second"))
    }

    @Test
    fun objOrNullIsNullForJsonNull() {
        val o = Json.parseToJsonElement("""{"a": null, "b": {"x": 1}}""").jsonObject
        assertEquals(null, o["a"].objOrNull())
        assertEquals(null, o["missing"].objOrNull())
        assertTrue(o["b"].objOrNull() != null)
    }
}
