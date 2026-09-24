package net.ripster.mobile.ui.premium

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож проводки: КАЖДЫЙ стиль плеера обязан читать план режима и стеклить ИМ
 * СВОИ ОРГАНЫ УПРАВЛЕНИЯ.
 *
 * Причина появления — жалоба владельца «я не вижу стеклянного дизайна в
 * плеере, нет пружинной анимации» при включённом режиме. Режим был вшит в один
 * из трёх стилей, и настройка честно обещала картинку, которой в двух других
 * стилях не было вовсе. Молчаливая дыра такого рода не ловится ни одним тестом
 * на эффект: стекло считается правильно, а экран, который его не зовёт, просто
 * живёт своей жизнью.
 *
 * Вторая половина стражи — обратная жалоба той же сборки: режим рисовал
 * ПЕЛЕНУ на весь экран и называл это стеклом. Вердикт владельца: «стекло это не
 * фон, это не навес на весь экран, это стиль самого плеера». Поэтому здесь
 * проверяется не только «стекло позвали», но и «фона режима нигде нет».
 *
 * Проверка структурная и читает исходники: плеер опознаётся по входу
 * `state: NowPlayingState` (любой новый стиль придёт с ним). Добавить
 * четвёртый стиль, пропустив режим, по-прежнему можно — но молча это пройти уже
 * не сможет.
 *
 * Запуск из каталога модуля (`:app:testDebugUnitTest`); из другого корня
 * исходников не видно, и сторож молча уходит — как остальные сторожа проекта.
 */
class PlayerPremiumWiringTest {

    private val screens = File("src/main/java/net/ripster/mobile/ui/screens")
    private val premiumDir = File("src/main/java/net/ripster/mobile/ui/premium")
    private val shell = File("src/main/java/net/ripster/mobile/ui/AppShell.kt")

    /** Файлы, которые рисуют экран «Сейчас играет». */
    private fun playerScreens(): List<File> =
        if (!screens.exists()) emptyList()
        else screens.listFiles { f: File -> f.extension == "kt" }!!
            .filter { it.readText().contains("state: NowPlayingState,") }
            .sortedBy { it.name }

    @Test
    fun `player styles were found to check`() {
        if (!screens.exists()) return
        val names = playerScreens().map { it.name }
        assertTrue(
            "сторож не нашёл ни одного плеера — он проверяет не то: $names",
            names.containsAll(
                listOf(
                    "ImmersivePlayerScreen.kt", "LiquidPlayerScreen.kt",
                    "NowPlayingScreen.kt", "ReferencePlayerScreen.kt",
                ),
            ),
        )
    }

    @Test
    fun `every player style reads the plan through the shared helper`() {
        if (!screens.exists()) return
        val offences = playerScreens()
            .filterNot { it.readText().contains("rememberPremiumPlayerFx(") }
            .map { it.name }
        assertTrue(
            "эти стили плеера не знают про «дорогие визуалы» — настройка обещает эффект, а экран его не рисует:\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `every player style glasses its own controls`() {
        if (!screens.exists()) return
        // Стекло и пружина — два обещания режима, и оба теперь про САМ ОРГАН, а
        // не про панель над фоном: `liquidGlass(` рисует кнопку/чип/пилюлю/сик-
        // бар стеклом (тон из-под него, блик, кромка, внутренняя тень), а
        // `glassSquish(` пружинит его под пальцем. Прежняя плёнка `premiumGlass(`
        // (полупрозрачный слой поверх размытого фона) из стилей ушла — кто вернёт
        // её на органы, тот вернёт и «серую пелену», за которую режим и выставили.
        val offences = mutableListOf<String>()
        playerScreens().forEach { f ->
            val text = f.readText()
            if (!text.contains("liquidGlass(")) offences += "${f.name}: органы не нарисованы стеклом (нет liquidGlass()"
            if (!text.contains("glassSquish(")) offences += "${f.name}: органы не пружинят под пальцем (нет glassSquish()"
            if (text.contains("premiumGlass(")) offences += "${f.name}: снова клеит premiumGlass — стекло должно рисовать сам орган"
        }
        assertTrue(
            "режим обещан, но не доведён до органов управления:\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `the status line is not dressed as glass`() {
        if (!shell.exists()) return
        // Стекло — это ОРГАН УПРАВЛЕНИЯ (кнопка, чип, пилюля, сик-бар), у него
        // есть кромка по периметру и блик по верхней грани. Статусной строке
        // загрузок делать из этого нечего: на «1 готово» активных задач нет,
        // орб схлопывается в нулевую высоту, и стеклянная плашка на всю ширину
        // превращалась в волосяной прямоугольник вокруг 15dp текста, а блик
        // ложился ПОВЕРХ подписи — «сломанное стекло с обрезанным верхом»
        // (эмулятор, 23.09.2026). Рамка на весь экран здесь положена одной
        // верхней шапке; строка носит свою тихую пилюлю по размеру текста.
        val bars = Regex("""glassBar\(""").findAll(shell.readText()).count()
        assertTrue(
            "стеклянная рамка на весь экран осталась только на верхней шапке, " +
                "а их в оболочке: $bars",
            bars == 1,
        )
    }

    @Test
    fun `no player screen paints a premium wash`() {
        if (!screens.exists()) return
        // Любой слой «режимного» фона поверх экрана — это ровно та мутная пелена,
        // за которую режим и выставили. Плееру нечего красить: только свои панели.
        val banned = listOf(
            "PremiumPlayerBackdrop(", "PremiumBackdrop(", "premiumOwnBackdrop(",
        )
        val offences = playerScreens().flatMap { f ->
            val text = f.readText()
            banned.filter { text.contains(it) }.map { "${f.name}: красит фон режима ($it)" }
        }
        assertTrue(
            "стекло — это стиль плеера, а не навес на весь экран:\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `the mode cannot draw a background at all anymore`() {
        if (!premiumDir.exists()) return
        // Сторож строже предыдущего: фона режима нет ни в одном файле пакета,
        // поэтому его нельзя «вернуть на место» одним вызовом в одном экране.
        val offences = premiumDir.listFiles { f: File -> f.extension == "kt" }!!
            .filter {
                val text = it.readText()
                "enum class Backdrop" in text || "fun PremiumBackdrop" in text ||
                    "rememberVisualsActive" in text
            }
            .map { it.name }
        assertTrue(
            "в ui/premium снова завёлся полноэкранный фон режима:\n" + offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `the background behind the glass is the style's own layer`() {
        if (!shell.exists() || !screens.exists()) return
        // Стили, которые самодостаточны и перекрывают ambilight, обязаны сами
        // объявить слой-источник; прозрачный стиль берёт чужой. В обоих случаях
        // стекло размывает то, что под ним действительно видно.
        val text = shell.readText()
        val ambient = Regex("""ambientHaze\s*=\s*premiumHaze""").findAll(text).count()
        assertTrue(
            "AppShell отдаёт свой ambilight только тому стилю, который под собой " +
                "ничего не красит (Reference), а не всем сразу: $ambient",
            ambient in 1..1,
        )
        val registered = playerScreens()
            .filter { it.readText().contains("liquidGlassSource(") }
            .map { it.name }
        assertTrue(
            "эти стили красят фон поверх ambilight и обязаны сами объявить слой " +
                "стекла, иначе панели размывают то, чего не видно:\n" + registered.joinToString("\n"),
            registered.size >= 2,
        )
    }
}
