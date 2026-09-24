package net.ripster.mobile.ui.theme

import java.io.File
import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.tr
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подписи ряда действий обязаны ДОЧИТЫВАТЬСЯ.
 *
 * Снимок владельца 23.09.2026 (плеер «Studio», русский интерфейс): «Трек-л…»,
 * «Эквала…», «На кол…». Семь кнопок в один ряд при 411dp дают ячейку 51dp, а
 * «Эквалайзер» при 12sp — это ≈60dp. Многоточие в кнопке действия означает, что
 * человек выбирае́т наугад: «На кол…» — это «на колонку» или «на всё, что
 * найдётся»?
 *
 * Тест берёт РЕАЛЬНЫЕ ярлыки из словаря (все пять языков) и РЕАЛЬНУЮ раскладку
 * ряда из NowPlayingScreen, и прогоняет их через тот же `CaptionFit`, которым
 * ряд воспользуется в рантайме. Экран самый узкий из поддерживаемых (320dp —
 * Galaxy A31 тестировщика) и шрифт самый крупный из доступных в приложении
 * (масштаб 1.3): если подпись выдержала их, она выдержит и обычный случай.
 */
class CaptionFitTest {

    /** Узкий телефон владельца и максимальный системный масштаб шрифта. */
    private val narrowScreenDp = 320f                // Galaxy A31 тестировщика
    private val narrowRowDp = narrowScreenDp - 52f  // колонна «Studio»: поля по 26.dp
    private val gapDp = 4f
    private val fontScale = 1.3f
    private val captionSp = 12f                   // type.caption в RipsterDensity.Normal

    /** Ряд плеера вызывается с тем же низом ячейки, что и в NowPlayingScreen. */
    private fun actionColumns(count: Int, rowWidthDp: Float) = CaptionFit.columnsFor(
        count = count,
        rowWidthDp = rowWidthDp,
        gapDp = gapDp,
        minSlotDp = CaptionFit.ActionRowMinSlotDp,
    )

    @Test
    fun studioActionLabelsAreReadableOnTheNarrowestPhone() {
        val keys = studioActionKeys()
        assertTrue("не нашёл ярлыков ряда — ряд переписали, тест надо переписать", keys.isNotEmpty())
        val columns = actionColumns(keys.size, narrowRowDp)
        val slot = CaptionFit.slotDp(narrowRowDp, minOf(columns, keys.size), gapDp)
        val offenders = mutableListOf<String>()
        for (key in keys) {
            for (lang in AppLang.entries) {
                val label = tr(key, lang)
                val size = CaptionFit.fitSizeSp(label, slot, captionSp, maxLines = 2, fontScale = fontScale)
                val need = CaptionFit.widthDp(label, size, fontScale)
                if (need > slot * 2f) {
                    offenders += "$key/${lang.tag}: «$label» needs ${"%.0f".format(need)}dp, slot $slot x2"
                }
            }
        }
        assertTrue(
            "подпись действия не дочитывается в ячейку — срезается до «Эквала…\".\n" +
                "Сократи ярлык во всех пяти языках либо подними число строк/ширину.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** На узком экране ряд обязан разложиться, а не сжаться в нечитаемое. */
    @Test
    fun studioActionRowReflowsWhenCellsGetTooTight() {
        val columns = actionColumns(7, narrowRowDp)
        assertTrue(
            "на ${"%.0f".format(narrowRowDp)}dp семи ячейкам достаётся по ${"%.1f".format(CaptionFit.slotDp(narrowRowDp, 7, gapDp))}dp — " +
                "меньше низа ряда ${CaptionFit.ActionRowMinSlotDp}: ряд обязан лечиться в 4+3",
            columns < 7,
        )
        val slot = CaptionFit.slotDp(narrowRowDp, columns, gapDp)
        assertTrue("после раскладки ячейка всё равно тесная: $slot", slot >= CaptionFit.ComfortableSlotDp)
        // На широком экране ряд остаётся одним — раскладка не самоцель.
        assertTrue(
            "на 411dp ряд обязан остаться в одну строку",
            actionColumns(7, 411f - 52f) == 7,
        )
    }

    /** Подпись таба — одна строка всегда (высота табов не должна прыгать). */
    @Test
    fun bottomNavLabelsFitOneLineAfterShrinking() {
        val keys = File(sourceRoot(), "ui/navigation/BottomNav.kt").readText()
            .let { src -> Regex("""TabSpec\([^,]+,\s*"([^"]+)"""").findAll(src).map { it.groupValues[1] }.toList() }
        assertTrue("не нашёл ярлыки табов", keys.isNotEmpty())
        // Табы идут край в край: у бара нет горизонтальных полей (AppShell
        // вызывает BottomNav без паддинга), поэтому ячейка = ширина экрана / N.
        val slot = narrowScreenDp / keys.size
        val offenders = mutableListOf<String>()
        for (key in keys) {
            for (lang in AppLang.entries) {
                val label = tr(key, lang)
                val size = CaptionFit.fitSizeSp(label, slot, captionSp, maxLines = 1, fontScale = fontScale)
                if (CaptionFit.widthDp(label, size, fontScale) > slot) {
                    offenders += "$key/${lang.tag}: «$label» при ${"%.1f".format(size)}sp всё ещё шире $slot"
                }
            }
        }
        assertTrue(
            "ярлык таба не влезает в свою ячейку даже после сжатия — сокращай строку " +
                "в словаре (озвучка останется полной: contentDescription берётся тем же tr, " +
                "поэтому сокращай только показываемое слово, а не ключ):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ── здравый смысл самой оценки ───────────────────────────────────────────

    @Test
    fun estimatorIsMonotonicAndBounded() {
        assertTrue("пустая строка не должна стоить ширины", CaptionFit.widthDp("", 12f) == 0f)
        assertTrue(
            "широкая глифа обязана мериться больше узкой",
            CaptionFit.widthDp("illl", 12f) < CaptionFit.widthDp("MMMM", 12f),
        )
        assertTrue(
            "CJK — полная ширина",
            CaptionFit.widthDp("曲", 12f) > CaptionFit.widthDp("i", 12f) * 2f,
        )
        val wide = CaptionFit.fitSizeSp("Эквалайзер", 80f, captionSp)
        val tight = CaptionFit.fitSizeSp("Эквалайзер", 40f, captionSp)
        assertTrue("чем теснее ячейка, тем меньше кегль, а не наоборот: $wide / $tight", wide >= tight)
        assertTrue("кегль вылез за максимум темы", wide <= captionSp)
        assertTrue("кегль провалился под читаемый минимум", tight >= CaptionFit.MinSizeSp)
    }

    /**
     * Матра присоединяется к согласной, а не рисует отдельную глифу: «ক+া» —
     * один кластер. Первая версия оценки считала весь деванагари полной шириной,
     * и «लाइब्रेरी» выходила длиннее, чем «Библиотека», — то есть сторож врал
     * и приговаривал к многоточию там, где подпись влезает.
     */
    @Test
    fun combiningMarksCostLessThanTheirBaseLetter() {
        val base = "क"      // ка — основа согласной
        val matra = "ा"     // а — присоединённая гласная (Mc)
        val halant = "्"     // халант (Mn) — висящий знак
        val w = CaptionFit.widthDp(base, 12f)
        assertTrue("основа деванагари не должна стоить больше CJK", w <= 12f * 0.95f)
        assertTrue("халант не отдельная глифа", CaptionFit.widthDp(halant, 12f) < w * 0.3f)
        assertTrue("матра уже своей согласной", CaptionFit.widthDp(matra, 12f) < w)
        assertTrue(
            "кластер «क+ा» уже двух независимых букв",
            CaptionFit.widthDp(base + matra, 12f) < w * 2f,
        )
    }

    /** Ярлыки ряда действий — прямо из исходника экрана, а не переписанные сюда. */
    private fun studioActionKeys(): List<String> {        val src = File(sourceRoot(), "ui/screens/NowPlayingScreen.kt").readText()
        val block = src.substringAfter("val studioActions = listOf(", "")
            .substringBefore("\n    )")
        return Regex("""tr\("([a-z0-9_.]+)"""").findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/net/ripster/mobile"),
            File("app/src/main/java/net/ripster/mobile"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("не нашёл исходники: ${candidates.map { it.absolutePath }}")
    }
}
