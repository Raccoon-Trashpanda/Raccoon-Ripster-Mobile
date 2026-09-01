package net.ripster.mobile.core.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import net.ripster.mobile.core.model.Service

/**
 * Реестр клиентов сервисов — прямой аналог `REGISTRY` в
 * `ripster/engines/registry.py`. Клиенты регистрируются здесь при старте
 * приложения; остальной код спрашивает клиента по [Service], а не создаёт его.
 *
 * Пока реализаций нет — их добавляет Этап 1 (SoundCloud) и дальше. Реестр
 * заведён сейчас, чтобы слой загрузки и слой сервисов не ссылались друг на
 * друга напрямую.
 */
object ServiceRegistry {

    private val clients = LinkedHashMap<Service, ServiceClient>()

    /**
     * «Поколение» реестра — растёт при каждой перерегистрации клиентов
     * ([net.ripster.mobile.RipsterApp.registerClients], зовётся после ввода
     * токена в Настройках). Экран поиска подписан на него и переспрашивает
     * `configured()` без перезапуска приложения — это и есть безперезагрузочный
     * режим внедрения токенов.
     */
    val generation = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun register(client: ServiceClient) {
        clients[client.service] = client
    }

    /** Позвать ОДИН раз в конце пакета register(...) — будит подписчиков. */
    fun bumpGeneration() {
        generation.value = generation.value + 1
    }

    fun get(service: Service): ServiceClient? = clients[service]

    fun all(): List<ServiceClient> = clients.values.toList()

    /**
     * Сервисы, у которых сейчас есть рабочий доступ (токен/логин). Проверки
     * идут ПАРАЛЛЕЛЬНО: `isConfigured()` у части клиентов — это сетевой вызов
     * (скрейп client_id, login, refresh), и последовательно они складывались
     * в 10–15 с чёрного экрана поиска.
     */
    suspend fun configured(): List<ServiceClient> = kotlinx.coroutines.coroutineScope {
        all().map { client ->
            async {
                // Один зависший сетевой isConfigured() не должен держать весь
                // экран поиска в «Проверяю сервисы…» — жёсткий потолок 6 с.
                val ok = withTimeoutOrNull(6_000) {
                    runCatching { client.isConfigured() }.getOrDefault(false)
                } ?: false
                if (ok) client else null
            }
        }.awaitAll().filterNotNull()
    }

    /** Найти клиент, который берётся разобрать эту ссылку. */
    suspend fun resolverFor(url: String): Pair<ServiceClient, Any>? {
        for (client in all()) {
            val selection = client.resolve(url) ?: continue
            return client to selection
        }
        return null
    }
}
