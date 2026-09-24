package net.ripster.mobile.core.errors

/**
 * Сетевая ли это авария — по самому исключению и по всей цепочке причин.
 *
 * Предикат заведён отдельно от [errorText][net.ripster.mobile.ui.i18n.errorText],
 * потому что «нет связи» и «плохой токен» путаются в двух слоях, и каждый раз
 * человек идёт чинить не то:
 *
 *  - `AppleProxyClient.itunes` ловил `UnknownHostException` и возвращал пустой
 *    список; экран поиска на пустом ответе от единственного сервиса советует
 *    «проверь токен в настройках» — без сети тоже (этап 9, BUG-9);
 *  - движки, которые бросают `IOException("<маркер> …")`, иногда пишут рядом с
 *    маркером сетевую причину, и тогда «обнови токен» при выключенном Wi-Fi
 *    бессмысленно: сеть надо проверять ПЕРВОЙ, до диагностики авторизации.
 *
 * Голый `java.io.IOException` сетью НЕ считается: им оборачивают и HTTP-коды,
 * и битый JSON, и прочий мусор. Сеть — это либо конкретный класс (адрес не
 * разложился, соединение сброшено или не established, таймаут), либо сообщение
 * с явным сетевым почерком.
 *
 * Цепочку причин проходим целиком: OkHttp и корутины часто кладут настоящий
 * `UnknownHostException` в `cause`, а наружу отдают враппер.
 */
fun isNetworkFailure(t: Throwable?): Boolean {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 10) {
        if (cur is java.net.UnknownHostException || cur is java.net.ConnectException ||
            cur is java.net.SocketException || cur is java.net.SocketTimeoutException ||
            cur is java.io.InterruptedIOException || cur is javax.net.ssl.SSLException
        ) return true
        if (networkish(cur.message)) return true
        cur = cur.cause?.takeIf { it !== cur }
        depth++
    }
    return false
}

private val NETWORK_SIGNS = listOf(
    "unknownhost", "unable to resolve host", "no route to host",
    "ehostunreach", "enetunreach", "enotconn",
    "network is unreachable", "network unreachable", "networkerror",
    "failed to connect", "connect fail", "connection refused", "connection abort",
    "connection reset", "connection closed", "broken pipe", "socket error",
    "timed out", "timeout", "unreachable",
)

private fun networkish(msg: String?): Boolean {
    val m = msg?.lowercase() ?: return false
    if (m.startsWith("__e.")) return isConnectivityMarker(m)   // хвост под маркером — чужой текст
    return NETWORK_SIGNS.any { it in m }
}

/**
 * Маркеры, которые САМИ по себе означают «не достучались»: `pc_offline` — связь
 * с ПК пропала, `pc_fetch_failed` — файл есть, но забрать не удалось,
 * `health_unknown` — спросить сервис не удалось. Они кладут сетевую причину в
 * хвост (`AppleProxyClient` пишет `code(PC_OFFLINE, it.message)`), и глушить
 * этот случай — значит отправить человека без сети чинить сопряжение.
 */
private val CONNECTIVITY_MARKERS = listOf(
    EngineErrors.PC_OFFLINE, EngineErrors.PC_FETCH_FAILED, EngineErrors.HEALTH_UNKNOWN,
).map { it.lowercase() }

private fun isConnectivityMarker(m: String): Boolean = CONNECTIVITY_MARKERS.any { m.startsWith(it) }
