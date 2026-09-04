package net.ripster.mobile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier

/**
 * Плавная прокрутка длинного названия — как в десктопной версии.
 *
 * Раньше так умел только мини-плеер, а три полноэкранных плеера обрезали строку
 * многоточием: длинное название на большом экране, где места как раз много,
 * читалось хуже, чем на маленькой плашке над навигацией. Жалоба владельца
 * 04.09.2026.
 *
 * Параметры собраны здесь, а не скопированы в четыре места: прокрутка должна
 * идти одинаково во всех плеерах, иначе переход из мини в полный выглядит как
 * смена приложения. Пауза перед стартом обязательна — без неё строка уезжает
 * раньше, чем её успевают прочитать, и короткая пауза между проходами нужна по
 * той же причине.
 *
 * [startDelayMs] сдвигают намеренно: если название и исполнитель поедут
 * синхронно, две бегущие строки рядом читаются как рябь. Исполнитель трогается
 * позже названия.
 *
 * Прокручивается только то, что не влезло: `basicMarquee` на короткой строке
 * не делает ничего, поэтому применять его можно безусловно.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.ripsterMarquee(startDelayMs: Int = 1400): Modifier =
    this
        .fillMaxWidth()
        .basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = startDelayMs,
            repeatDelayMillis = 1600,
        )

/** Задержка для второй строки (исполнитель), чтобы не ехала вровень с первой. */
const val MARQUEE_SECOND_LINE_DELAY = 1800
