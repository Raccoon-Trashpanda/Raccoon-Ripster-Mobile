package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.pair.PcBridge
import net.ripster.mobile.core.settings.CredentialStore

/**
 * Автопочинка учёток на телефоне — то же, что делает сторож на ПК.
 *
 * Владелец 12.09.2026: «то же самое в мобилке». На ПК учётки меряются, мёртвые
 * снимаются, а основная заменяется на ту, что реально отдаёт lossless. Телефон
 * до этого не умел ничего: «Подключён» означало «поле заполнено», и протухший
 * токен выяснялся посреди загрузки.
 *
 * Починка здесь возможна ровно одна, и это не недоработка, а устройство:
 * лишних учёток у телефона нет, зато рядом есть ПК, который ими управляет и
 * УЖЕ отдаёт лучшую по каждому сервису (`routes/pairing.py`). Поэтому лечение
 * — забрать свежие учётки с ПК и перемерить. Нет пары с ПК — телефон честно
 * сообщает, что чинить нечем, а не делает вид, что всё хорошо.
 */
object AccountAutoHeal {

    /** Что вышло по одному сервису. */
    data class Result(
        val service: Service,
        val before: AccountHealth,
        val after: AccountHealth? = null,
        /** Тянули ли свежие учётки с ПК из-за этого сервиса. */
        val healed: Boolean = false,
    ) {
        /** Стало ли лучше: была беда — стало рабочим и с lossless. */
        val improved: Boolean
            get() = after != null && after.usable && after.lossless &&
                !(before.usable && before.lossless)
    }

    /** Учётку стоит лечить: сервис её отверг ИЛИ точно сказал, что lossless не даст.
     *
     * «Точно сказал» здесь существенно: у клиента, который про качество ничего
     * не сообщил (`quality` пуст), `lossless = false` означает «не знаю», а не
     * «нет». Лечить по такому значило бы дёргать ПК за новыми ключами из-за
     * сервиса, который просто не умеет себя мерить.
     */
    private fun needsHelp(h: AccountHealth): Boolean =
        h.alive == false ||
            (h.alive == true && !h.lossless && h.quality.isNotBlank() && h.losslessPossible)

    /**
     * Померить все настроенные сервисы и, если есть чем помочь, вылечить.
     *
     * [pull] вызывается ОДИН раз на весь обход, даже если проблемных сервисов
     * несколько: учётки приезжают с ПК одним пакетом, и дёргать синхронизацию
     * на каждый сервис значило бы гонять одно и то же по кругу.
     */
    suspend fun run(
        store: CredentialStore,
        bridge: PcBridge,
        paired: Boolean,
    ): List<Result> {
        val first = mutableListOf<Result>()
        for (client in ServiceRegistry.all()) {
            val ok = runCatching { client.isConfigured() }.getOrDefault(false)
            if (!ok) continue
            val h = runCatching { client.health() }
                .getOrElse { AccountHealth.unknown("проверка не выполнилась: ${it::class.simpleName}") }
            first += Result(client.service, h)
        }

        val broken = first.filter { needsHelp(it.before) }
        // «Не смогли спросить» не лечим: сеть или геоблок — не свойство учётки,
        // и тянуть из-за них новые ключи значит чинить исправное.
        if (broken.isEmpty() || !paired) return first

        val pulled = runCatching { bridge.syncCredentials(store) }.getOrNull()?.isSuccess == true
        if (!pulled) return first

        // Перемеряем ТОЛЬКО пострадавшие: остальные уже ответили, и повторный
        // опрос стоил бы лишних запросов к сервисам.
        return first.map { r ->
            if (!needsHelp(r.before)) return@map r
            val client = ServiceRegistry.get(r.service) ?: return@map r
            val after = runCatching { client.health() }
                .getOrElse { AccountHealth.unknown("повторная проверка не выполнилась") }
            r.copy(after = after, healed = true)
        }
    }
}
