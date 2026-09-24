package net.ripster.mobile.ui.theme

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Влезает ли подпись в отведённое ей место — и каким кеглем её рисовать.
 *
 * Откуда это взялось (снимок владельца 23.09.2026, плеер «Studio», русский
 * интерфейс): «Трек-л…», «Эквала…», «На кол…». Семь действий встали в один ряд
 * `weight(1f)`, на 411dp каждой ячейке достаётся ≈51dp, а подпись при
 * `type.caption` (12sp) для «Эквалайзер» требует ≈60dp. Многоточие честно
 * говорит «не влезло», но для человека оно означает «разработчик не померил».
 *
 * Настоящего `TextMeasurer` здесь нет намеренно: оценка должна быть доступна
 * юнит-тесту без Robolectric и без эмулятора, иначе сторож превращается в
 * заметку. Поэтому — консервативная метрика по классам глифов:
 *  · CJK считается полной шириной (0.95em): иероглиф заведомо шире латинской
 *    строчной, а недооценка здесь означает обрезку;
 *  · индийские письма — кластеры: основа согласной ≈0.8em, присоединённая
 *    матра — не отдельная глифа (см. MarkRatio/SpacingMarkRatio);
 *  · узкие глифы (i, l, t, точка, дефис) — треть em;
 *  · «m», «w» и заглавные — широкие;
 *  · остальные строчные — 0.56em, это примерно на 10% шрифт-независимой оценки
 *    с запасом: лишний процент сжатия почти не виден, обрезанное слово видно.
 *
 * Запас выбран в сторону ПЕРЕоценки ширины: подпись чуть меньше — заметит только
 * тот, кто ищет; подпись с многоточием — теряет смысл («На кол…» — это не «на
 * колонку»).
 */
object CaptionFit {

    /** Нижняя граница кегля. Ниже — уже не читается, а угадывается. */
    const val MinSizeSp: Float = 8.5f

    private const val WideRatio = 0.95f
    private const val NarrowRatio = 0.33f
    private const val UpperRatio = 0.68f
    private const val DigitRatio = 0.58f
    private const val LowerRatio = 0.56f
    private const val SpaceRatio = 0.30f
    private const val VeryWideRatio = 0.86f

    // Знаки, которые НЕ рисуются отдельной глифой: म + ा — это одна кластерная
    // форма, а не две буквы рядом. Считать их полной шириной (как это делала
    // первая версия, где весь деванагари шёл по WideRatio) значило завышать
    // размер индийской подписи в ~1.6 раза и ставить ей приговор «не влезает»
    // там, где она влезает. Mn/Me — висящие знаки (халант, нукта, анусвара),
    // Mc — «отступные» гласные: они шире нуля, но заметно уже согласной.
    private const val MarkRatio = 0.15f
    private const val SpacingMarkRatio = 0.45f

    /** Основа деванагари-буквы: чуть уже CJK, потому что связана матрами. */
    private const val IndicRatio = 0.80f

    private val narrowChars = "iljtfrI.,!|'’()·-:;\"`"

    // Широкие глифы: латиничные m/w и заглавные, плюс кирилличные Щ Ш Ю Ф Ж,
    // Ñ и é. Написано escape'ами не от осторожности к редактору, а ради
    // I18nAuditTest: он ищет кириллицу в литералах боевого кода, и здесь он
    // прав — это не подпись, а классификатор глифов, но молча исключать файл
    // из проверки дороже.
    private val veryWideChars = "mwMW\u0429\u0428\u042E\u0424\u0416\u00D1\u00E9"

    /** Ширина строки в dp при кегле [fontSizeSp] и системном масштабе [fontScale]. */
    fun widthDp(text: String, fontSizeSp: Float, fontScale: Float = 1f): Float =
        units(text) * fontSizeSp * fontScale

    private fun units(text: String): Float {
        var u = 0f
        for (c in text) u += ratioOf(c)
        return u
    }

    /**
     * Наибольший кегль из `[minSizeSp .. maxSizeSp]`, при котором текст влезает
     * в [slotWidthDp] за [maxLines] строк. Если не влезает даже в [minSizeSp] —
     * возвращается [minSizeSp] (и вызывающий остаётся на многоточии: это честный
     * исход, а не молчаливая обрезка по середине глифы).
     *
     * [maxLines] > 1 засчитывает перенос: Compose рвёт и слово длиннее строки,
     * иначе «Эквалайзер» на двух строках не помог бы.
     */
    fun fitSizeSp(
        text: String,
        slotWidthDp: Float,
        maxSizeSp: Float,
        minSizeSp: Float = MinSizeSp,
        maxLines: Int = 1,
        fontScale: Float = 1f,
    ): Float {
        if (text.isEmpty() || slotWidthDp <= 0f) return maxSizeSp
        val lines = max(1, maxLines)
        val em = units(text)
        if (em <= 0f) return maxSizeSp
        // Нужен размер, при котором lines * slotWidth покрывает em * size * scale.
        val fits = (slotWidthDp * lines) / (em * max(0.5f, fontScale))
        return min(maxSizeSp, max(minSizeSp, floor(fits * 2f) / 2f))
    }

    /**
     * Влезает ли подпись целиком при данном кегле. Определение то же, что у
     * [fitSizeSp], — чтобы тест и рантайм не разошлись в оценках.
     */
    fun fits(
        text: String,
        slotWidthDp: Float,
        fontSizeSp: Float,
        maxLines: Int = 1,
        fontScale: Float = 1f,
    ): Boolean = fitSizeSp(text, slotWidthDp, fontSizeSp, minSizeSp = 0f, maxLines = maxLines, fontScale = fontScale) >= fontSizeSp

    /**
     * Сколько ячеек поставить в ряд, если всего их [count].
     *
     * Семь подряд на узком экране — это не «плотный ряд», а семь обрезанных
     * подписей. Если ячейке достаётся меньше [minSlotDp], ряд раскладывается по
     * более крупным ячейкам (и, соответственно, в несколько рядов): всё остаётся
     * видимым, подписи дочитываются, скролла нет.
     *
     * Ряд дробится ПОРОВНУ (`7 → 4+3`, а не `6+1`): одинокая кнопка во втором
     * ряду читается как потерянная, и ровно на этот случай ниже стоит `ceil`.
     *
     * [minPerRow] — низ, ниже которого ряды уже не дробятся: три по 40dp на
     * 360dp — это семь экранов вертикали вместо двух рядов, что хуже обрезанной
     * подписи.
     */
    fun columnsFor(
        count: Int,
        rowWidthDp: Float,
        gapDp: Float,
        minSlotDp: Float = ComfortableSlotDp,
        minPerRow: Int = 3,
    ): Int {
        if (count <= 1) return max(1, count)
        var rows = 1
        while (true) {
            val columns = ceil(count / rows.toFloat()).toInt()
            if (columns < minPerRow || rows > count) return min(count, minPerRow)
            if (slotDp(rowWidthDp, columns, gapDp) >= minSlotDp) return columns
            rows++
        }
    }

    /** Ширина одной ячейки из [count] штук в ряду шириной [rowWidthDp]. */
    fun slotDp(rowWidthDp: Float, count: Int, gapDp: Float): Float =
        if (count <= 1) rowWidthDp else (rowWidthDp - gapDp * (count - 1)) / count

    /**
     * То же, что [fitSizeSp], но с кеглем из темы и системным масштабом шрифта —
     * чтобы вызывающему не приходилось в каждом экране заново тянуть density.
     * Возвращает уже [TextUnit], готовый для `TextStyle.fontSize`.
     */
    @androidx.compose.runtime.Composable
    fun fitSp(text: String, slotWidthDp: Float, maxLines: Int = 1): TextUnit {
        val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        return fitSizeSp(
            text = text,
            slotWidthDp = slotWidthDp,
            maxSizeSp = RipsterTheme.type.caption.value.toFloat(),
            maxLines = maxLines,
            fontScale = scale,
        ).sp
    }

    /**
     * Сколько строк отдать подписям РЯДА: одна, если при минимальном кегле все
     * ярлыки влезают, иначе две — сразу для всего ряда.
     *
     * Считается по ряду, а не по кнопке: одна двухстрочная подпись рядом с
     * четырьмя однострочными сдвинула бы иконки, и ряд перестал бы быть рядом.
     */
    @androidx.compose.runtime.Composable
    fun rowCaptionLines(labels: List<String>, slotWidthDp: Float): Int {
        val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        return if (labels.any { needsWrap(it, slotWidthDp, scale) }) 2 else 1
    }

    /** Кегль для всего ряда — самый маленький из подходящих каждому ярлыку. */
    @androidx.compose.runtime.Composable
    fun rowCaptionSp(labels: List<String>, slotWidthDp: Float, maxLines: Int): TextUnit {
        val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val maxSp = RipsterTheme.type.caption.value.toFloat()
        var size = maxSp
        for (label in labels) {
            size = min(size, fitSizeSp(label, slotWidthDp, maxSp, maxLines = maxLines, fontScale = scale))
        }
        return size.sp
    }

    /** Ширина ячейки, ниже которой подпись перестаёт влезать. */
    const val ComfortableSlotDp: Float = 56f

    /**
     * Низ для ряда действий плеера. Он ниже [ComfortableSlotDp] намеренно: семь
     * кнопок в одну линию — это язык плеера «Studio», и терять его из-за пары
     * десятых dp не стоит. Раскладывается ряд только тогда, когда ячейка
     * делается уже такой, что подпись в ней не читается даже в две строки.
     */
    const val ActionRowMinSlotDp: Float = 46f

    /**
     * Не влезает ли подпись в [slotWidthDp] даже при минимальном кегле — то
     * есть нужен ли перенос. Спрашивается ДО подбора кегля: размер строки
     * вычисляется уже для выбранного числа строк, и порядок здесь важен.
     */
    fun needsWrap(text: String, slotWidthDp: Float, fontScale: Float = 1f): Boolean =
        widthDp(text, MinSizeSp, fontScale) > slotWidthDp

    private fun ratioOf(c: Char): Float {
        // Категории Character приходят как Byte — сравниваем через Int.
        val type = java.lang.Character.getType(c).toInt()
        return when {
            c == ' ' -> SpaceRatio
            type == NonSpacingMark || type == EnclosingMark -> MarkRatio
            type == CombiningSpacingMark -> SpacingMarkRatio
            c.code in 0x0900..0x097F -> IndicRatio                        // деванагари: основа кластера
            c.code in 0x3000..0x9FFF || c.code in 0xAC00..0xD7AF -> WideRatio  // CJK/кана/хангыль
            c in narrowChars -> NarrowRatio
            c in veryWideChars -> VeryWideRatio
            c.isDigit() -> DigitRatio
            c.isUpperCase() -> UpperRatio
            else -> LowerRatio
        }
    }

    private val NonSpacingMark: Int = java.lang.Character.NON_SPACING_MARK.toInt()
    private val CombiningSpacingMark: Int = java.lang.Character.COMBINING_SPACING_MARK.toInt()
    private val EnclosingMark: Int = java.lang.Character.ENCLOSING_MARK.toInt()
}
