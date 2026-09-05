package net.ripster.mobile.core.library

import net.ripster.mobile.core.db.LibraryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Уборка библиотеки стирает записи — ошибка здесь стоит фонотеки, поэтому
 * правило проверяется тестом, а не наблюдением за телефоном.
 *
 * Основано на том, что реально лежало в базе на A31 (05.09.2026): три строки
 * на один файл в кэше, которого ОС давно нет, и пары строк на один путь.
 */
class LibraryUpkeepTest {

    private var seq = 0

    private fun row(path: String, added: Long, id: String = "task-${seq++}") = LibraryEntity(
        id = id,
        title = "T$seq",
        artist = "A",
        album = null,
        serviceId = "qobuz",
        container = "flac",
        bitrateKbps = null,
        filePath = path,
        sizeBytes = 1,
        artworkUrl = null,
        addedAt = added,
    )

    @Test
    fun aVanishedFileLosesItsRecord() {
        val p = "/data/user/0/net.ripster.mobile/cache/qb_255933316.flac"
        val plan = LibraryUpkeep.plan(listOf(row(p, 1), row(p, 2), row(p, 3))) { false }
        assertEquals(listOf(p), plan.forget)
        assertTrue("стёртое не должно возвращаться", plan.keep.isEmpty())
    }

    @Test
    fun threeRecordsOfOneFileBecomeOne() {
        val p = "/storage/Music/Navjaxx/Cyberverse/01 - Cyberverse.flac"
        val plan = LibraryUpkeep.plan(listOf(row(p, 10), row(p, 30), row(p, 20))) { true }
        assertEquals(listOf(p), plan.forget)
        assertEquals(1, plan.keep.size)
        assertEquals("остаётся самая свежая", 30L, plan.keep.first().addedAt)
        assertEquals(LibraryUpkeep.idFor(p), plan.keep.first().id)
    }

    @Test
    fun anUnreadableLocationIsLeftAlone() {
        """SAF-адрес без выданного доступа: «не смог спросить» — это НЕ «файла
        нет». Стирать по такому ответу — потерять чужую фонотеку."""
        val p = "content://com.android.externalstorage/tree/primary%3AMusic/x.flac"
        val plan = LibraryUpkeep.plan(listOf(row(p, 1), row(p, 2))) { null }
        assertTrue(plan.forget.isEmpty())
        assertTrue(plan.keep.isEmpty())
    }

    @Test
    fun anAlreadyTidyRecordIsNotRewritten() {
        val p = "/storage/Music/ok.flac"
        val plan = LibraryUpkeep.plan(listOf(row(p, 1, LibraryUpkeep.idFor(p)))) { true }
        assertTrue(plan.forget.isEmpty())
        assertTrue(plan.keep.isEmpty())
    }

    @Test
    fun aSingleRecordWithATaskIdGetsAStableOne() {
        """Пока ключ — id задачи загрузки, повторное скачивание того же файла
        заведёт ВТОРУЮ строку: именно так и набежали дубли."""
        val p = "/storage/Music/one.flac"
        val plan = LibraryUpkeep.plan(listOf(row(p, 5, "6f1c-task-uuid"))) { true }
        assertEquals(listOf(p), plan.forget)
        assertEquals("одна запись — это не дубль", 0, plan.duplicates)
        assertEquals(LibraryUpkeep.idFor(p), plan.keep.single().id)
    }

    @Test
    fun differentFilesNeverShareAnId() {
        assertTrue(LibraryUpkeep.idFor("/a/x.flac") != LibraryUpkeep.idFor("/a/y.flac"))
        assertEquals(LibraryUpkeep.idFor("/a/x.flac"), LibraryUpkeep.idFor("/a/x.flac"))
    }

    @Test
    fun theWholeMessFromThePhoneIsSortedInOnePass() {
        val ghost = "/data/user/0/net.ripster.mobile/cache/qb_255933316.flac"
        val dup = "/storage/Music/Massive Attack/Mezzanine (Deluxe)/03 - Teardrop.flac"
        val fine = "/storage/Music/The Killers/Mr. Brightside/Mr. Brightside.mp3"
        val rows = listOf(
            row(ghost, 1), row(ghost, 2), row(ghost, 3),
            row(dup, 10), row(dup, 11),
            row(fine, 20, LibraryUpkeep.idFor(fine)),
        )
        val plan = LibraryUpkeep.plan(rows) { p -> p != ghost }
        assertEquals(setOf(ghost, dup), plan.forget.toSet())
        assertEquals(1, plan.keep.size)
        assertEquals(dup, plan.keep.single().filePath)
        assertEquals("призраков убрано", 1, plan.ghosts)
        assertEquals("дублей склеено", 1, plan.duplicates)
        assertEquals("перевыдача ключа — не дубль", 1, plan.keep.size)
    }

    @Test
    fun anEmptyLibraryNeedsNothing() {
        val plan = LibraryUpkeep.plan(emptyList()) { true }
        assertTrue(plan.forget.isEmpty() && plan.keep.isEmpty())
    }
}
