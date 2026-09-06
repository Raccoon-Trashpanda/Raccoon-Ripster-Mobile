package net.ripster.mobile.core.diag

import android.content.Context
import android.os.Build
import net.ripster.mobile.RipsterApp
import net.ripster.mobile.core.service.ServiceRegistry
import net.ripster.mobile.core.settings.CredentialStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Отчёт о неполадке: одна кнопка — весь контекст.
 *
 * Зачем. Жалоба тестера почти всегда звучит как «не работает», и дальше идёт
 * переписка на полдня: какая версия, какой сервис, что именно написало, что
 * было до этого. Всё это у приложения уже есть — не хватало способа отдать
 * это одним куском.
 *
 * ЧТО ПОПАДАЕТ В ОТЧЁТ
 *   1. Обстановка: версия, устройство, Android, язык, место на диске.
 *   2. Учётки: задана / не задана / отвергнута сервисом — сам факт, не значение.
 *   3. Сервисы: настроен ли клиент.
 *   4. Очередь: сколько чего и последние отказы с причинами.
 *   5. Журнал своего процесса (чужой Android и не отдаст).
 *
 * ЧТО НЕ ПОПАДАЕТ НИКОГДА
 *   Значения секретов. Не «замаскированные по шаблону», а вырезанные
 *   ДОСЛОВНО: [CredentialStore.redactionPairs] отдаёт настоящие строки, и
 *   каждая заменяется именем своего поля. Шаблон («что-то длинное похоже на
 *   токен») ловит не всё, а отчёт человек отправляет в чужой чат — цена
 *   промаха здесь не «некрасиво», а «утёк доступ». Поверх дословной замены
 *   работает ещё и шаблонная: на случай значений, которых у нас в сторе нет
 *   (чужой токен из ответа сервиса, подписанная ссылка).
 *
 * Текст отчёта — английский. Это лог, а логи в проекте английские (за
 * русские строки в них уже ловил `I18nAuditTest`); подписи кнопок на экране
 * — наоборот, через `tr()`, как всё остальное в интерфейсе.
 */
object Diagnostics {

    /** Сколько строк журнала берём. Больше — буфер обмена начинает отказывать. */
    private const val LOG_LINES = 1500

    /** Потолок отчёта: длиннее не примут ни буфер обмена, ни мессенджер. */
    private const val MAX_CHARS = 180_000

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    suspend fun build(context: Context): String {
        val app = runCatching { RipsterApp.from(context) }.getOrNull()
        val sb = StringBuilder(64 * 1024)

        sb.appendLine("=== RIPSTER MOBILE · TROUBLE REPORT ===")
        sb.appendLine("collected: ${stamp.format(Date())}")
        sb.appendLine()

        sb.appendLine("-- app --")
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            sb.appendLine("version: ${pi.versionName} (${pi.versionCode})")
        }.onFailure { sb.appendLine("version: unreadable (${it.javaClass.simpleName})") }
        sb.appendLine("package: ${context.packageName}")
        sb.appendLine()

        sb.appendLine("-- device --")
        sb.appendLine("model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("abis: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("system locale: ${Locale.getDefault()}")
        runCatching {
            sb.appendLine("free space: ${context.filesDir.usableSpace / (1024 * 1024)} MB")
        }
        sb.appendLine()

        sb.appendLine("-- credentials --")
        // Только ФАКТ наличия и вердикт сервиса. Ни длины, ни первых символов:
        // «токен на 86 знаков» — уже подсказка тому, кто подбирает.
        val creds = app?.credentials
        if (creds == null) {
            sb.appendLine("store unavailable")
        } else {
            sb.appendLine("encrypted store: ${if (creds.usingEncryption) "yes" else "NO (fallback mode)"}")
            for (k in CredentialStore.Key.entries) {
                val has = creds.get(k) != null
                sb.appendLine(
                    "${k.id}: " + when {
                        !has -> "not set"
                        creds.isRejected(k) -> "set, but the service REJECTED it"
                        else -> "set"
                    },
                )
            }
        }
        sb.appendLine()

        sb.appendLine("-- services --")
        val clients = runCatching { ServiceRegistry.all() }.getOrDefault(emptyList())
        if (clients.isEmpty()) sb.appendLine("no client registered")
        for (c in clients) {
            val ok = runCatching { c.isConfigured() }.getOrElse { false }
            sb.appendLine("${c.service.id}: ${if (ok) "configured" else "not configured"}")
        }
        sb.appendLine()

        sb.appendLine("-- download queue --")
        val dao = app?.db?.downloads()
        if (dao == null) {
            sb.appendLine("database unavailable")
        } else {
            val rows = runCatching { dao.recent(200) }.getOrDefault(emptyList())
            sb.appendLine("rows (last 200): ${rows.size}")
            rows.groupingBy { it.state }.eachCount().forEach { (st, n) -> sb.appendLine("  $st: $n") }
            val failed = rows.filter { it.state == "FAILED" }.take(20)
            if (failed.isNotEmpty()) {
                sb.appendLine("recent failures:")
                for (f in failed) {
                    sb.appendLine("  [${f.serviceId}] ${f.artist} — ${f.title}")
                    sb.appendLine("      reason: ${f.errorReason ?: "not recorded"}")
                }
            }
        }
        sb.appendLine()

        sb.appendLine("-- process log --")
        sb.appendLine(logcat())

        // Вычистка — ПОСЛЕДНИМ шагом, по всему тексту разом. Так ни одна ветка
        // сборки отчёта не может случайно обойти её стороной.
        val redacted = redact(sb.toString(), creds?.redactionPairs().orEmpty())
        return if (redacted.length <= MAX_CHARS) redacted
        else redacted.take(MAX_CHARS) + "\n… report truncated at $MAX_CHARS chars"
    }

    /**
     * Журнал СВОЕГО процесса. Чужой Android и не отдаст — с 4.1 приложение
     * читает только свои строки, и это ровно то, что нужно.
     */
    private fun logcat(): String = runCatching {
        readLog(listOf("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}", "-t", "$LOG_LINES"))
            .ifBlank { "(empty — the process log has not filled up yet)" }
    }.getOrElse {
        // `--pid` поддержан не на всех прошивках. Тогда берём общий хвост:
        // Android всё равно отдаёт только наши строки.
        runCatching { readLog(listOf("logcat", "-d", "-v", "time", "-t", "$LOG_LINES")) }
            .getOrElse { e -> "could not read the log: ${e.javaClass.simpleName}: ${e.message}" }
    }

    private fun readLog(cmd: List<String>): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        return BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
    }

    /**
     * Убрать секреты: сперва дословно известные, затем по шаблонам.
     *
     * Порядок важен. Дословная замена точна и лишнего не трогает; шаблонная
     * широка и служит страховкой ровно для того, чего мы не знаем.
     */
    internal fun redact(text: String, pairs: List<Pair<String, String>>): String {
        var out = text
        for ((secret, label) in pairs) out = out.replace(secret, label)

        // ЗАГОЛОВКИ, У КОТОРЫХ СЕКРЕТ — ВСЁ ЗНАЧЕНИЕ.
        // Тут думать не о чем: после `Authorization:` полезного не бывает,
        // режем до конца строки (вместе со схемой `Bearer`).
        out = Regex("""(?im)^(.*\b(?:authorization|x-user-auth-token)\b\s*[:=]\s*).+$""")
            .replace(out) { "${it.groupValues[1]}<redacted>" }

        // СХЕМА + ТОКЕН: «Bearer abc…», «OAuth abc…» посреди строки.
        out = Regex("""(?i)\b(bearer|oauth)\s+([A-Za-z0-9._\-]{8,})""")
            .replace(out) { "${it.groupValues[1]} <redacted>" }

        // ПОЛЕ С ИМЕНЕМ: «oauth: <длинное>», «token = <длинное>».
        //
        // Значение обязано быть ДЛИННЫМ (8+ знаков) — и это не придирка.
        // Без такого условия шаблон съедал собственные строки отчёта:
        // «tidal.oauth: set» становилось «tidal.oauth: <redacted>», то есть
        // «учётка задана» делалось неотличимо от «не задана», и отчёт врал
        // сам про себя (поймано на A31 06.09.2026). Настоящие токены
        // короткими не бывают, а свои значения вырезаны дословно шагом выше.
        out = Regex("""(?i)\b(oauth|token|secret|password)\b\s*[:=]\s*(\S{8,})""")
            .replace(out) { "${it.groupValues[1]}: <redacted>" }
        out = Regex("""(?i)([?&](?:token|auth_token|access_token|arl|sp_dc|hmac|sig|request_sig|password)=)[^&\s"]+""")
            .replace(out) { "${it.groupValues[1]}<redacted>" }

        // Почта: домен оставляем — по нему видно сервис, имя не нужно никому.
        out = Regex("""[A-Za-z0-9._%+\-]+@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})""")
            .replace(out) { "<email>@${it.groupValues[1]}" }

        // ПОСЛЕДНЯЯ СЕТЬ: длинные «токеноподобные» куски, которых в сторе нет
        // (чужой токен из ответа сервиса, подписанная ссылка).
        //
        // Планка — 32 знака И цифра И буква. Сперва стояло 24, и сеть начала
        // ловить обычный текст: «DefaultDispatcher-worker-1» в каждой строке
        // журнала превращался в «Defa…<redacted>». Нечитаемый отчёт бесполезен
        // ровно так же, как отсутствующий, — а имена потоков и путей секретами
        // не являются (проверено на A31 06.09.2026).
        //
        // Честно про остаток риска: неизвестный секрет длиной 24–31 знак сюда
        // не попадёт. Свои значения вырезаны дословно шагом выше, а чужие
        // приезжают в заголовках и параметрах, которые накрыты отдельно, — так
        // что эта сеть страхует, а не держит всё одна.
        out = Regex("""\b(?=[A-Za-z0-9_\-]*[A-Za-z])(?=[A-Za-z0-9_\-]*[0-9])[A-Za-z0-9_\-]{32,}\b""")
            .replace(out) { m -> m.value.take(4) + "…<redacted ${m.value.length} chars>" }

        return out
    }
}
