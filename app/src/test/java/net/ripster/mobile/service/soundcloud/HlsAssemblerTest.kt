package net.ripster.mobile.service.soundcloud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import net.ripster.mobile.core.errors.EngineErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Сборка HLS в один файл: спуск по мастер-плейлисту, разрешение относительных
 * ссылок и отказ на шифровании.
 *
 * Проверяется против локального HTTP-заглушечника на 127.0.0.1 — без интернета,
 * но и без подмены `bestVariant`/`absolute`: они приватные, и единственный честный
 * способ их спросить — то, что реально ушло в сеть и что легло на диск.
 */
class HlsAssemblerTest {

    private class MiniHls : AutoCloseable {
        private val server = ServerSocket(0)
        val routes = ConcurrentHashMap<String, Pair<Int, ByteArray>>()
        val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val base = "http://127.0.0.1:${server.localPort}"
        @Volatile private var alive = true

        init {
            val t = Thread {
                while (alive) {
                    val sock = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    try {
                        sock.use { s ->
                            val line = BufferedReader(InputStreamReader(s.inputStream)).readLine() ?: return@use
                            val target = line.split(" ")[1]
                            val path = target.substringBefore('?')
                            synchronized(requested) { requested.add(target) }
                            val (status, body) = routes[path] ?: (404 to "missing".toByteArray())
                            val head = "HTTP/1.1 $status OK\r\n" +
                                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            s.getOutputStream().use { out ->
                                out.write(head.toByteArray())
                                out.write(body)
                                out.flush()
                            }
                        }
                    } catch (e: Exception) {
                        // обрыв соединения не обязан валить сервер
                    }
                }
            }
            t.isDaemon = true
            t.start()
        }

        fun page(path: String, body: String) {
            routes[path] = 200 to body.toByteArray()
        }

        fun bytes(path: String, body: ByteArray, status: Int = 200) {
            routes[path] = status to body
        }

        fun status(path: String, code: Int) {
            routes[path] = code to "nope".toByteArray()
        }

        override fun close() {
            alive = false
            runCatching { server.close() }
        }
    }

    private fun out(): File = File.createTempFile("hls-", ".m4a").apply { deleteOnExit() }

    private fun run(url: String, file: File): List<HlsAssembler.Progress> =
        runBlocking { HlsAssembler.assemble(url, file).toList() }

    private fun expectFailure(url: String, file: File): String {
        try {
            run(url, file)
        } catch (e: IOException) {
            return e.message ?: e.toString()
        }
        fail("ожидался IOException, а сборка удалась")
        return ""
    }

    // ── спуск по мастер-плейлисту ────────────────────────────────────────────

    @Test
    fun a_master_playlist_descends_to_the_richest_variant() {
        MiniHls().use { h ->
            h.page("/abr/master.m3u8", """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
                low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=256000,CODECS="mp4a.40.2"
                high.m3u8
            """.trimIndent())
            h.page("/abr/high.m3u8", "#EXTM3U\n#EXTINF:4,\nseg-h1.ts\n")
            h.page("/abr/low.m3u8", "#EXTM3U\n#EXTINF:4,\nseg-l1.ts\n")
            h.bytes("/abr/seg-h1.ts", "HI".toByteArray())
            h.bytes("/abr/seg-l1.ts", "LO".toByteArray())

            run(h.base + "/abr/master.m3u8", out())
            assertTrue("запрошен верхний вариант: ${h.requested}", "/abr/high.m3u8" in h.requested)
            assertFalse("бедный вариант не качается", "/abr/low.m3u8" in h.requested)
        }
    }

    @Test
    fun a_relative_variant_href_is_resolved_against_the_master() {
        MiniHls().use { h ->
            h.page("/abr/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nsub/variant.m3u8\n")
            h.page("/abr/sub/variant.m3u8", "#EXTM3U\ns1.ts\n")
            h.bytes("/abr/sub/s1.ts", "AA".toByteArray())
            run(h.base + "/abr/master.m3u8", out())
            assertTrue(h.requested.toString(), "/abr/sub/s1.ts" in h.requested)
        }
    }

    @Test
    fun an_absolute_variant_href_beats_the_base() {
        MiniHls().use { h ->
            h.page("/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n${h.base}/elsewhere/v.m3u8\n")
            h.page("/elsewhere/v.m3u8", "#EXTM3U\ns1.ts\n")
            h.bytes("/elsewhere/s1.ts", "AA".toByteArray())
            run(h.base + "/master.m3u8", out())
            assertTrue(h.requested.toString(), "/elsewhere/s1.ts" in h.requested)
        }
    }

    @Test
    fun dot_dot_in_a_relative_href_walks_up_the_path() {
        MiniHls().use { h ->
            h.page("/abr/deep/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n../top/v.m3u8\n")
            h.page("/abr/top/v.m3u8", "#EXTM3U\n../shared/s1.ts\n")
            h.bytes("/abr/shared/s1.ts", "AA".toByteArray())
            run(h.base + "/abr/deep/master.m3u8", out())
            assertTrue(h.requested.toString(), "/abr/shared/s1.ts" in h.requested)
        }
    }

    @Test
    fun segments_resolve_against_the_media_playlist_not_the_master() {
        MiniHls().use { h ->
            h.page("/abr/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvariant/v.m3u8\n")
            h.page("/abr/variant/v.m3u8", "#EXTM3U\ns1.ts\n")
            h.bytes("/abr/variant/s1.ts", "AA".toByteArray())
            run(h.base + "/abr/master.m3u8", out())
            assertFalse("не в корень плейлиста: ${h.requested}", "/abr/s1.ts" in h.requested)
            assertTrue(h.requested.toString(), "/abr/variant/s1.ts" in h.requested)
        }
    }

    @Test
    fun a_token_in_the_playlist_query_is_not_carried_into_every_segment() {
        MiniHls().use { h ->
            h.page("/a/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8\n")
            h.page("/a/v.m3u8", "#EXTM3U\ns1.ts\n")
            h.bytes("/a/s1.ts", "AA".toByteArray())
            run(h.base + "/a/master.m3u8?token=secret", out())
            assertTrue(h.requested.toString(), "/a/s1.ts" in h.requested)
            assertTrue(
                "запрос плейлиста остаётся со своим токеном",
                h.requested.any { it.startsWith("/a/v.m3u8") },
            )
        }
    }

    @Test
    fun equal_bandwidth_keeps_the_first_variant_it_saw() {
        MiniHls().use { h ->
            h.page("/master.m3u8", """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=128000
                first.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=128000
                second.m3u8
            """.trimIndent())
            h.page("/first.m3u8", "#EXTM3U\nf.ts\n")
            h.page("/second.m3u8", "#EXTM3U\ns.ts\n")
            h.bytes("/f.ts", "F".toByteArray())
            h.bytes("/s.ts", "S".toByteArray())
            run(h.base + "/master.m3u8", out())
            assertTrue(h.requested.toString(), "/f.ts" in h.requested)
        }
    }

    @Test
    fun a_master_without_a_single_variant_is_named_not_swallowed() {
        MiniHls().use { h ->
            h.page("/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n")
            val msg = expectFailure(h.base + "/master.m3u8", out())
            assertTrue(msg, "without variants" in msg)
        }
    }

    /**
     * Кандидат в баги: шапка обещает «вариант с наибольшим BANDWIDTH», а поиск
     * `BANDWIDTH=(\d+)` находит первое вхождение — то есть число из
     * `AVERAGE-BANDWIDTH`, если оно стоит в строке раньше.
     */
    @Test
    fun averageBandwidthFirstIsNotMistakenForBandwidth() {
        MiniHls().use { h ->
            h.page("/master.m3u8", """
                #EXTM3U
                #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=64000,BANDWIDTH=256000,CODECS="mp4a.40.2"
                rich.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
                poor.m3u8
            """.trimIndent())
            h.page("/rich.m3u8", "#EXTM3U\nr.ts\n")
            h.page("/poor.m3u8", "#EXTM3U\np.ts\n")
            h.bytes("/r.ts", "RICH".toByteArray())
            h.bytes("/p.ts", "POOR".toByteArray())
            val file = out()
            run(h.base + "/master.m3u8", file)
            assertEquals("RICH", file.readText())
        }
    }

    // ── шифрование ───────────────────────────────────────────────────────────

    @Test
    fun an_encrypted_playlist_is_refused_before_anything_is_downloaded() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\ns1.ts\n")
            h.bytes("/s1.ts", "XX".toByteArray())
            val msg = expectFailure(h.base + "/a.m3u8", out())
            assertTrue(msg, EngineErrors.DRM_UNSUPPORTED in msg)
            assertTrue("сегменты не качаются: ${h.requested}", h.requested == listOf("/a.m3u8"))
        }
    }

    @Test
    fun a_clear_key_is_not_encryption() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\ns1.ts\n")
            h.bytes("/s1.ts", "OK".toByteArray())
            val file = out()
            run(h.base + "/a.m3u8", file)
            assertEquals("OK", file.readText())
        }
    }

    @Test
    fun encryption_hidden_inside_a_variant_is_still_caught() {
        MiniHls().use { h ->
            h.page("/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8\n")
            h.page("/v.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\"\ns1.ts\n")
            val msg = expectFailure(h.base + "/master.m3u8", out())
            assertTrue(msg, EngineErrors.DRM_UNSUPPORTED in msg)
        }
    }

    // ── склейка ──────────────────────────────────────────────────────────────

    @Test
    fun segments_are_concatenated_in_playlist_order() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\n#EXTINF:4,\none.ts\n#EXTINF:4,\ntwo.ts\n#EXTINF:4,\nthree.ts\n")
            h.bytes("/one.ts", "AAA".toByteArray())
            h.bytes("/two.ts", "BB".toByteArray())
            h.bytes("/three.ts", "C".toByteArray())
            val file = out()
            run(h.base + "/a.m3u8", file)
            assertEquals("AAABBC", file.readText())
            assertEquals(
                "порядок запросов = порядок в плейлисте",
                listOf("/a.m3u8", "/one.ts", "/two.ts", "/three.ts"), h.requested.toList(),
            )
        }
    }

    @Test
    fun progress_counts_segments_and_bytes_and_ends_at_one() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\none.ts\ntwo.ts\nthree.ts\n")
            h.bytes("/one.ts", "1234".toByteArray())
            h.bytes("/two.ts", "12".toByteArray())
            h.bytes("/three.ts", "X".toByteArray())
            val steps = run(h.base + "/a.m3u8", out())
            assertEquals(3, steps.size)
            assertEquals(1, steps[0].segmentsDone)
            assertEquals(3, steps[0].segmentsTotal)
            assertEquals(4L, steps[0].bytesWritten)
            assertEquals(6L, steps[1].bytesWritten)
            assertEquals(7L, steps[2].bytesWritten)
            assertEquals(1f, steps.last().fraction, 0f)
            assertEquals(1f / 3f, steps[0].fraction, 1e-6f)
        }
    }

    @Test
    fun comments_and_blank_lines_are_never_treated_as_segments() {
        MiniHls().use { h ->
            h.page(
                "/a.m3u8",
                "#EXTM3U\n#EXT-X-VERSION:3\n\n#EXTINF:4.0,\n  one.ts  \n#EXT-X-ENDLIST\n\n",
            )
            h.bytes("/one.ts", "SOLO".toByteArray())
            val file = out()
            val steps = run(h.base + "/a.m3u8", file)
            assertEquals("SOLO", file.readText())
            assertEquals(1, steps.size)
        }
    }

    @Test
    fun crlf_line_endings_parse_like_lf() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\r\none.ts\r\ntwo.ts\r\n")
            h.bytes("/one.ts", "AA".toByteArray())
            h.bytes("/two.ts", "BB".toByteArray())
            val file = out()
            run(h.base + "/a.m3u8", file)
            assertEquals("AABB", file.readText())
        }
    }

    @Test
    fun a_playlist_without_a_single_segment_is_reported_not_recorded_as_silence() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\n#EXT-X-ENDLIST\n")
            val file = out()
            val msg = expectFailure(h.base + "/a.m3u8", file)
            assertTrue(msg, "empty m3u8" in msg)
            assertEquals(0L, file.length())
        }
    }

    @Test
    fun an_empty_media_playlist_below_a_master_is_also_reported() {
        MiniHls().use { h ->
            h.page("/master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8\n")
            h.page("/v.m3u8", "#EXTM3U\n")
            val msg = expectFailure(h.base + "/master.m3u8", out())
            assertTrue(msg, "empty m3u8" in msg && "/v.m3u8" in msg)
        }
    }

    // ── сеть ─────────────────────────────────────────────────────────────────

    @Test
    fun a_failing_playlist_names_the_url_and_the_code() {
        MiniHls().use { h ->
            h.status("/a.m3u8", 500)
            val msg = expectFailure(h.base + "/a.m3u8", out())
            assertTrue(msg, "/a.m3u8" in msg && "500" in msg)
        }
    }

    @Test
    fun a_failing_segment_is_called_a_segment() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\none.ts\ntwo.ts\n")
            h.bytes("/one.ts", "AA".toByteArray())
            h.status("/two.ts", 404)
            val msg = expectFailure(h.base + "/a.m3u8", out())
            assertTrue(msg, msg.startsWith("segment "))
        }
    }

    @Test
    fun a_segment_that_fails_leaves_a_truncated_file_not_a_fake_full_one() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\none.ts\ntwo.ts\n")
            h.bytes("/one.ts", "AA".toByteArray())
            h.status("/two.ts", 500)
            val file = out()
            expectFailure(h.base + "/a.m3u8", file)
            assertEquals(2L, file.length())
        }
    }

    @Test
    fun the_progress_of_a_failed_run_stops_where_the_stream_stopped() {
        MiniHls().use { h ->
            h.page("/a.m3u8", "#EXTM3U\none.ts\ntwo.ts\nthree.ts\n")
            h.bytes("/one.ts", "AAA".toByteArray())
            h.bytes("/two.ts", "BB".toByteArray())
            h.status("/three.ts", 500)
            val seen = mutableListOf<HlsAssembler.Progress>()
            try {
                runBlocking {
                    HlsAssembler.assemble(h.base + "/a.m3u8", out()).collect { seen.add(it) }
                }
                fail("ожидался IOException")
            } catch (e: IOException) {
                // так и надо
            }
            assertEquals(2, seen.size)
            assertEquals(5L, seen.last().bytesWritten)
        }
    }

    @Test
    fun fraction_survives_a_partial_report() {
        val p = HlsAssembler.Progress(3, 4, 1024L)
        assertEquals(0.75f, p.fraction, 1e-6f)
        assertEquals(1024L, p.bytesWritten)
    }
}
