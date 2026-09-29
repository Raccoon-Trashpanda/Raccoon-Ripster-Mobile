package net.ripster.mobile.core.reactions

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Провод, а не модуль (`AGENTS.md`: «проверяй ПРОВОД, а не модуль»).
 *
 * `PlayerController` в JVM-тесте не строится — им нужен `Context` и Media3-сессия
 * (в проекте уже есть такой приём: `SessionSeekableTest` читает
 * исходник текстом). Здесь той же мерой проверяется, что реакции НЕ остались
 * красивым слоем, который никто не вызывает:
 *
 *  - плеер объявлять приёмник и вызывать его на скип, на лайк и на «дослушал»;
 *  - `RipsterApp` привязывать и запись, и чтение (тот же способ, что у вкуса и
 *    журнала прослушивания);
 *  - обе станции (плитка на Главной и автодобор в очереди) передавать реакции
 *    в `StationBuilder.build`;
 *  - лайк из UI идти через тот же провод, а не в свою отдельную копилку.
 */
class ReactionWiringTest {

    /**
     * Корень модуля в JVM-тесте бывает разным (из IDE — `app/`, из Gradle —
     * корень проекта), поэтому перебираем те же кандидаты, что и
     * `SessionSeekableTest`, а не один жёсткий путь.
     */
    private val roots = listOf(
        File("app/src/main/java"), File("src/main/java"), File("../app/src/main/java"),
    )

    private fun read(vararg rest: String): String {
        val rel = rest.joinToString(File.separator)
        val hit = roots.map { File(it, rel) }.firstOrNull { it.exists() }
            ?: error("нет $rel относительно ${File("").absolutePath}")
        return hit.readText()
    }

    private fun player(): String =
        read("net", "ripster", "mobile", "player", "PlayerController.kt")

    private fun app(): String =
        read("net", "ripster", "mobile", "RipsterApp.kt")

    @Test
    fun thePlayerPublishesSkipsLikesAndFullListens() {
        val p = player()
        assertTrue("плеер не объявляет приёмник реакций", p.contains("bindReactionLog"))
        assertTrue("скип не помечается", p.contains("fun noteSkip("))
        assertTrue("лайк не помечается", p.contains("fun noteLike("))
        assertTrue("дослушанное не помечается", p.contains("noteFullListenFromState"))
        // сам вызов, а не только объявление: «дальше» = скип того, что играло
        val nextBody = p.substringAfter("fun next() {").substringBefore("\n    }")
        assertTrue("`next()` не зовёт noteSkip(): \"$nextBody\"", nextBody.contains("noteSkip()"))
        val tick = p.substringAfter("while (true) {").substringBefore("}\n        }")
        assertTrue("тик не зовёт noteFullListenFromState()", tick.contains("noteFullListenFromState()"))
    }

    @Test
    fun theAppWiresBothEndsOfTheBuffer() {
        val a = app()
        assertTrue("RipsterApp не привязывает запись реакций",
                   a.contains("player.bindReactionLog"))
        assertTrue("RipsterApp не привязывает чтение реакций",
                   a.contains("player.bindStationReactions"))
        assertTrue("реакции пишутся не в буфер настроек",
                   a.contains("Reactions.append(this, r)"))
        assertTrue("станции не читают буфер реакций",
                   a.contains("Reactions.load(this)"))
    }

    @Test
    fun bothStationEntryPointsPassReactionsToTheBuilder() {
        val home = read("net", "ripster", "mobile", "ui", "screens", "HomeScreen.kt")
        assertTrue("плитка станции не передаёт реакции", home.contains("reactions = Reactions.load(app)"))
        val p = player()
        assertTrue("автодобор очереди не передаёт реакции",
                   p.contains("reactions = reactionsProvider"))
    }

    @Test
    fun theLikeButtonSharesThePlayersWire() {
        val screen = read("net", "ripster", "mobile", "ui", "screens", "ReferencePlayerScreen.kt")
        assertTrue("кнопка «лайк» не отправляет реакцию в плеер",
                   screen.contains("app.player.noteLike()"))
    }
}
