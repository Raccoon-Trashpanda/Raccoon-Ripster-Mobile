package net.ripster.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Цвет живых экранов берётся из палитры темы, а не из литерала.
 *
 * Смысл один: человек переключил тему, а экран, нарисованный Color(0x…) или
 * Color.White поверх холста темы, переключение НЕ заметил — текст пропал на
 * светлом (или на тёмном). Полностью запретить литералы нельзя: часть цвета
 * — про картинку, а не про тему (оверлеи поверх обложек, скримы диалогов,
 * сам винил на заставке, чужая веб-страница логина). Поэтому сторож работает
 * как трещотка: файлам из списка разрешено РОВНО то число строк с литералами, что есть
 * сейчас. Новый литерал в разрешённом файле поднимет лимит — значит, его
 * нужно либо убрать в палитру, либо осознанно поднять и объяснить.
 *
 * ui/theme исключён целиком: палитры и живут там, и проверяются своим
 * PaletteContrastTest.
 */
class HardcodedColorAuditTest {

    private val literal = Regex("""Color\(0x|Color\.White|Color\.Black|Color\.parseColor""")

    private val allowed = mapOf(
        // Плеер — поверхность из обложки, а не из темы: белое/чёрное там
        // лежит на картинке. Экраны «Сейчас играет» правятся отдельным
        // заходом (worktree apple-grade-player), лимиты зафиксированы, чтобы
        // новый литерал не приполз незаметно.
        "NowPlayingScreen.kt" to 21,
        "ImmersivePlayerScreen.kt" to 36,
        "ReferencePlayerScreen.kt" to 3,
        // «Флюид» и его стекло: глифы и стеклянные органы лежат на тёмной
        // поверхности из палитры обложки (как Studio/Immersive), а primitives —
        // блик/кромка/тень и фолбэк-тон, то есть физика поверхности, не тема.
        "LiquidPlayerScreen.kt" to 19,
        "LiquidGlassSurface.kt" to 5,
        "LiquidGlass.kt" to 3,
        "PremiumPlayerFx.kt" to 1,
        "CoverStage.kt" to 3,
        "LivingCover.kt" to 1,
        "DownloadOrb.kt" to 5,
        "WaveformSeek.kt" to 3,
        "PulseStrip.kt" to 1,          // PURPLE — задокументированный отдельный смысл
        "BufferingRing.kt" to 6,        // sweep кольца — по палитре обложки
        "Depth.kt" to 2,                // тень/блик — физика, не тема
        "ReleaseCard.kt" to 12,         // бейджи поверх обложек
        "HomeScreen.kt" to 8,           // WAVE_PALETTE плитки радио + подписи на обложках
        "AppShell.kt" to 5,             // скримы, ручка sheet над обложкой, fallback пиксельной палитры
        "BootSplash.kt" to 6,           // винил — тёмный предмет, а не фон
        "ToolsScreen.kt" to 1,          // паспарту под спектрограммой
        "RadarScreen.kt" to 1,          // затемнение диалога
        "LoginWebViewActivity.kt" to 4, // чужая сервисная страница, не наш интерфейс
    )

    @Test
    fun `hardcoded colors stay inside the sanctioned per-file budget`() {
        val root = File("src/main/java/net/ripster/mobile/ui")
        if (!root.exists()) return                       // запуск из другого корня — не наше дело
        val offences = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.extension == "kt" && !it.path.contains("ui${File.separator}theme") }
            .forEach { f ->
                val n = f.readText().lineSequence().count { literal.containsMatchIn(it) }
                if (n == 0) return@forEach
                val cap = allowed[f.name]
                if (cap == null) {
                    offences += "${f.name}: $n строк с литералами цвета — в палитру или в список allowance с причиной"
                } else if (n > cap) {
                    offences += "${f.name}: строк с литералами $n, разрешено $cap"
                }
            }
        assertTrue(
            "хардкод цвета вылез за контракт:\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }
}
