package net.ripster.mobile.ui.premium

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * «Дорогие визуалы» — опциональный режим плеера. Включается настройкой и по
 * умолчанию ВЫКЛЮЧЕН: он не чинит то, что и так работает, а добавляет слои,
 * которые стоят дорого на конкретном железе. Поэтому решение о том, ЧЕГО из
 * этого набора дать устройству, вынесено в чистую функцию [resolve] и покрыто
 * таблицей тестов — на приборе такой выбор не проверить, а угадывать нельзя:
 * хуже «нет эффекта» только эффект, из-за которого плеер подвисает.
 *
 * РЕЖИМ РИСУЕТ ОРГАНЫ УПРАВЛЕНИЯ, А НЕ ЭКРАН. Фон плеера планом не двигается
 * вообще: при включённом режиме он ровно тот же, что и при выключенном.
 * Претензия владельца на предыдущую сборку была прямо об этом — «стекло это не
 * фон, это не навес на весь экран, это стиль самого плеера», — и мутная
 * пелена поверх «Сейчас играет» удалена как класс.
 *
 * Границы честные, а не «примерно с одиннадцатой»:
 *  · преломление на кромках стекла (AGSL, `RuntimeShader`) — с Android 13
 *    (API 33), раньше в системе нет RuntimeShader;
 *  · настоящее размытие того, что под панелью (`RenderEffect`, им пользуется и
 *    Haze) — с Android 12 (API 31);
 *  · всё, что ниже — полупрозрачный тон, кромка и блик: та же форма стекла,
 *    без глубины резкости.
 *
 * Отдельный вход — энергосбережение и системный «убрать анимацию». Режим
 * экономии заряда откатывает стекло на дешёвый слой (размытие стоит GPU-прохода
 * каждый кадр) и снимает пружины; при нулевой шкале анимаций не двигается
 * НИЧТО — там, где обычные анимации выключает сама система, наши `withFrame`
 * крутились бы по-прежнему, и это надо выключать руками.
 */

/**
 * Что именно дать этому устройству. Поля — не «включено/выключено» вообще, а
 * отдельный verdict на каждый эффект: устройство может получить преломляющее
 * стекло, но не получить пружины, и наоборот.
 *
 * @param glass насколько настоящим стеклом рисовать кнопки, чипы, сик-бар,
 *   мини-плеер и нижнюю навигацию.
 * @param tintsFromContent настройка «адаптивные цвета»: если снята, стекло
 *   остаётся нейтральным и не перенимает оттенок обложки, которая за ним.
 * @param springMotion пружина на обложке и на нажатиях в такт play/pause.
 * @param lyricsDepth рельеф синхронных текстов: соседние строки приглушены и
 *   размыты (API 31+).
 */
data class PremiumPlan(
    val glass: GlassTier,
    val tintsFromContent: Boolean,
    val springMotion: Boolean,
    val lyricsDepth: Boolean,
) {
    companion object {
        /** Режим выключен: ничего не меняем. */
        val Disabled = PremiumPlan(
            glass = GlassTier.Plain,
            tintsFromContent = false,
            springMotion = false,
            lyricsDepth = false,
        )
    }
}

object PremiumVisuals {

    /** `RuntimeShader` (а значит и преломление на кромках) — с Android 13. */
    const val MIN_SDK_REFRACTION: Int = LiquidGlass.MIN_SDK_REFRACTIVE

    /** RenderEffect (а значит и настоящий блюр у Haze) — с Android 12. */
    const val MIN_SDK_REAL_BLUR: Int = LiquidGlass.MIN_SDK_BLURRED

    /**
     * Единственное место, где решает весь режим. Чистая функция: ни одного
     * системного вызова, поэтому прогоняется таблицей тестов, а не «надеждой,
     * что на приборе выглядит нормально».
     *
     * Дешевле всего идти по [LiquidGlass.tierFor]: уровень стекла — тот же
     * вердикт, что и про версионные границы, и держать его в двух местах
     * значило бы расходиться им при каждой новой версии Android.
     */
    fun resolve(
        enabled: Boolean,
        sdkInt: Int,
        animationsOff: Boolean,
        batterySaver: Boolean,
        adaptiveColors: Boolean,
    ): PremiumPlan {
        if (!enabled) return PremiumPlan.Disabled
        return PremiumPlan(
            glass = LiquidGlass.tierFor(
                enabled = true,
                sdkInt = sdkInt,
                batterySaver = batterySaver,
            ),
            tintsFromContent = adaptiveColors,
            springMotion = !animationsOff && !batterySaver,
            lyricsDepth = sdkInt >= MIN_SDK_REAL_BLUR && !batterySaver,
        )
    }

    /**
     * Системная шкала длительности анимаций. `0` — человек попросил телефон не
     * анимировать вообще (для Compose-анимаций это делает сама система, а вот
     * для шейдера, который живёт на withFrameMillis, — только мы).
     */
    fun animationsOff(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** Режим энергосбережения телефона. */
    fun batterySaver(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode == true
    }.getOrDefault(false)

    /** SDK этого устройства. Отдельно — чтобы [resolve] оставался тестируемым. */
    fun deviceSdk(): Int = Build.VERSION.SDK_INT

    /**
     * Ключ строки, честно называющей стекло, которое этот телефон получит.
     * Отдельно и в [resolve]-терминах: обещание в настройках и картинка в
     * плеере читают один и тот же вердикт, поэтому не могут разойтись —
     * «обещали преломление, а рисует заливку» исключено самой структурой.
     */
    fun glassSummaryKey(tier: GlassTier): String = when (tier) {
        GlassTier.Plain -> "glass.off"
        GlassTier.Tinted -> "glass.tint"
        GlassTier.Blurred -> "glass.blur"
        GlassTier.Refractive -> "glass.liquid"
    }
}
