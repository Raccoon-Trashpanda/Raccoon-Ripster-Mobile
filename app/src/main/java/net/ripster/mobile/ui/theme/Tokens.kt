package net.ripster.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ровно три радиуса на весь продукт.
 *
 * В вебе их 24, из них 8 на кликабельных элементах — и это не стилистика, а
 * потеря сигнала: если скругление у кнопки, поля ввода и плашки разное, форма
 * перестаёт означать «сюда можно нажать». Семёрка выбрана не по вкусу: 72 из 164
 * объявлений в settings.html уже 7px, то есть это самая дешёвая миграция.
 */
object Radii {
    /** Всё управляющее: кнопки, поля, чипы, переключатели, пункты меню. */
    val RCtl: Dp = 7.dp

    /** Карточки и обложки. */
    val RCard: Dp = 14.dp

    /**
     * ТОЛЬКО неинтерактивные бейджи.
     *
     * Причина запрета на кликабельных: полная капсула — это выученный признак
     * «ярлык, статус, не трогать». Кнопка-таблетка отбирает у бейджа его
     * единственное отличие, и после этого статус неотличим от действия.
     */
    val RPill: Dp = 999.dp

    val CtlShape: Shape = RoundedCornerShape(RCtl)
    val CardShape: Shape = RoundedCornerShape(RCard)
    val PillShape: Shape = RoundedCornerShape(RPill)
}

/**
 * Минимальная мишень касания. КОНСТАНТА, не настройка и не поле темы.
 *
 * Плотность вправе уменьшить кегль и отступы — то есть то, КАК показано. Она не
 * вправе уменьшить площадь, по которой человек попадает пальцем: это утверждение
 * о физике руки, а не о вкусе. Компактный режим уплотняет вид за счёт зазоров
 * между элементами, а не за счёт их доступности.
 */
val MinTouchTarget: Dp = 48.dp

/** Толщина границы управляющих элементов. Одна на всё, чтобы граница читалась как класс, а не как украшение. */
val BorderWidth: Dp = 1.dp

/** Кольцо фокуса заметно шире границы, иначе фокус не отличить от обычного состояния. */
val FocusRingWidth: Dp = 2.dp

/**
 * Высоты кнопок ФИКСИРОВАНЫ и не зависят от плотности.
 *
 * Причина: высота — один из трёх каналов иерархии (см. Buttons.kt). Если
 * плотность начнёт её менять, главная кнопка в Compact станет ниже обычной в
 * Large, и канал сломается на стыке экранов с разными настройками.
 *
 * 44 и 40 — не десктопные 34/30 из спецификации: там мышь с точностью в пиксель,
 * здесь палец.
 */
object ControlHeight {
    val Primary: Dp = 44.dp
    val Secondary: Dp = 40.dp
}

/** Отступы по плотностям. Меняется зазор, не размер зоны нажатия. */
data class RipsterSpacing(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    /** Поле экрана слева и справа. */
    val gutter: Dp,
    /** Горизонтальные поля внутри кнопки. */
    val controlPadding: Dp,
    /** Внутреннее поле карточки. */
    val cardPadding: Dp,
)

fun spacingFor(density: RipsterDensity): RipsterSpacing = when (density) {
    RipsterDensity.Compact -> RipsterSpacing(
        xs = 2.dp, sm = 4.dp, md = 8.dp, lg = 12.dp, xl = 16.dp,
        gutter = 12.dp, controlPadding = 12.dp, cardPadding = 10.dp,
    )
    RipsterDensity.Normal -> RipsterSpacing(
        xs = 4.dp, sm = 6.dp, md = 10.dp, lg = 16.dp, xl = 24.dp,
        gutter = 16.dp, controlPadding = 16.dp, cardPadding = 14.dp,
    )
    RipsterDensity.Large -> RipsterSpacing(
        xs = 4.dp, sm = 8.dp, md = 14.dp, lg = 20.dp, xl = 32.dp,
        gutter = 20.dp, controlPadding = 20.dp, cardPadding = 18.dp,
    )
}

/**
 * Кегли по плотностям.
 *
 * В sp, а не в dp: системный масштаб шрифта — средство доступности, и текст
 * обязан ему подчиняться. Плотность Ripster умножается на системный масштаб, а не
 * заменяет его.
 */
data class RipsterType(
    val caption: TextUnit,
    val label: TextUnit,
    val body: TextUnit,
    val title: TextUnit,
    /**
     * Заголовок ровно ОДНОГО экрана — Now Playing. Не «крупный title», а
     * отдельная роль: единственное место, где на телефоне есть один
     * доминирующий заголовок без соседей того же веса на экране. Если
     * появится второй экран с герой-заголовком — он получит тот же токен,
     * а не свой собственный размер по соседству.
     */
    val display: TextUnit,
    /** Кегль бейджа качества — отдельно: он никогда не растёт вместе с заголовками. */
    val badge: TextUnit,
)

fun typeFor(density: RipsterDensity): RipsterType = when (density) {
    RipsterDensity.Compact -> RipsterType(
        caption = 11.sp, label = 12.sp, body = 13.sp, title = 15.sp, display = 24.sp, badge = 11.sp,
    )
    RipsterDensity.Normal -> RipsterType(
        caption = 12.sp, label = 13.sp, body = 15.sp, title = 17.sp, display = 28.sp, badge = 12.sp,
    )
    RipsterDensity.Large -> RipsterType(
        caption = 13.sp, label = 15.sp, body = 17.sp, title = 20.sp, display = 32.sp, badge = 13.sp,
    )
}

/**
 * Веса — тоже канал иерархии, поэтому они перечислены здесь, а не расставлены
 * по месту использования: разброс весов по файлам и есть то, как иерархия
 * размывается.
 */
object Weights {
    val Primary = FontWeight.W700
    val Standard = FontWeight.W600
    val Quiet = FontWeight.W500
    val Body = FontWeight.W400
}

/**
 * Разрядка подписи опасной кнопки.
 *
 * Была записана как `0.06.sp * 12` — арифметика вместо величины: читается как
 * «0.06em», а даёт 0.72.sp. Величина должна быть величиной, а не выражением,
 * которое надо считать в уме.
 */
val DangerLetterSpacing: TextUnit = 0.7.sp
