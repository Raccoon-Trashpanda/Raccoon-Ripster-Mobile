package net.ripster.mobile.service.tidal

/**
 * Передаёт развёрнутый DASH-план (список сегментов) плееру.
 *
 * Список ссылок в URI MediaItem не уместить: очередь и станции строятся из
 * строк, которые ещё и пишутся в SharedPreferences, а у длинного трека это
 * тысячи символов на каждый сегмент. Поэтому в URL едет только ключ ([tag]),
 * а сам план живёт здесь; `player/RipsterDataSource` по ключу собирает склейку.
 *
 * План протухает вместе с подписями в ссылках (Tidal даёт им несколько минут).
 * Это не поломка, а срок: после перезапуска приложения плеер получит «плана
 * нет» — и обязан сказать об этом прямо, а не играть молча.
 */
internal object TidalDashRegistry {

    private const val TAG = "tdash="

    /** Сколько планов держать: примерно столько треков может стоять в очереди стрима. */
    private const val MAX_KEEP = 32

    private val plans = LinkedHashMap<String, DashSegments>()

    @Synchronized
    fun put(segments: DashSegments): String {
        // Ключ случайный, а не номер: id плана попадает в URL, а URL — в логи
        // сторонних библиотек плеера; предсказуемый номер позволил бы по нему
        // перебрать чужие планы. Сами подписанные ссылки здесь не печатаются.
        val key = java.util.UUID.randomUUID().toString().take(8)
        plans[key] = segments
        while (plans.size > MAX_KEEP) plans.remove(plans.keys.first())
        return key
    }

    @Synchronized
    fun peek(key: String): DashSegments? = plans[key]

    @Synchronized
    fun clear() = plans.clear()

    /** Помечает URL как DASH-цепочку сегментов для DataSource плеера. */
    fun tag(url: String, key: String): String =
        if (url.contains("#")) "$url&$TAG$key" else "$url#$TAG$key"

    /** Ключ плана из фрагмента URL; null — это не DASH-цепочка. */
    fun keyOf(fragment: String?): String? =
        fragment?.split('&', ';')?.firstOrNull { it.startsWith(TAG) }?.removePrefix(TAG)
}
