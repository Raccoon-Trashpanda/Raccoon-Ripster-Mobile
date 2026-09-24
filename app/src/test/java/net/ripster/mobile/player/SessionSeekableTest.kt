package net.ripster.mobile.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-4 (23.09.2026): полоса перемотки не двигала позицию ни в одном стиле
 * плеера. Жест был жив — запрос терялся в media3: `SimpleBasePlayer` выдаёт
 * `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` только для элемента, помеченного
 * перематываемым, а `MediaItemData.Builder` по умолчанию ставит `false`.
 * `MediaController.seekTo()` при отсутствующей команде молча возвращается.
 *
 * Сам `SimpleBasePlayer` на чистой JVM не поднять (нужен Looper/android.jar),
 * поэтому сторож проверяет исходник: каждый элемент очереди сессии обязан
 * объявлять себя перематываемым.
 */
class SessionSeekableTest {

    private fun source(): String {
        val rel = "net/ripster/mobile/player/NativeSessionPlayer.kt"
        val f = listOf(File("app/src/main/java"), File("src/main/java"), File("../app/src/main/java"))
            .map { File(it, rel) }
            .firstOrNull { it.exists() }
            ?: error("нет $rel относительно ${File("").absolutePath}")
        return f.readText()
    }

    @Test
    fun sessionItemsAreSeekable() {
        val src = source()
        // Весь itemOf до getState: внутри есть вложенный MediaItem.Builder()…build(),
        // поэтому резать по первому .build() нельзя.
        val builder = src.substringAfter("MediaItemData.Builder(", "")
            .substringBefore("override fun getState", "")
        assertTrue(
            "элемент очереди сессии не объявлен перематываемым — seekTo() снова будет молча выброшен",
            builder.contains(".setIsSeekable(true)"),
        )
    }

    @Test
    fun seekInCurrentItemIsAdvertised() {
        assertTrue(source().contains("Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,"))
    }
}
