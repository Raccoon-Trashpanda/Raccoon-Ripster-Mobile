package net.ripster.mobile.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted

/**
 * Идёт ли прямо сейчас какая-нибудь работа.
 *
 * Владелец 06.09.2026: «бегущая полоска сверху должна бегать не только при
 * скачивании, но и при загрузке приложения, включении станций и прочем — как
 * ПК-Рипстер». До этого полоса смотрела ТОЛЬКО на очередь загрузок, поэтому
 * молчала во всех остальных ожиданиях: сборка станции идёт двадцать секунд, а
 * экран выглядит замершим.
 *
 * Счётчик, а не флаг: операции идут внахлёст (поиск во время сборки станции,
 * подгрузка радара во время открытия альбома), и вторая завершившаяся не должна
 * гасить полосу, пока первая ещё работает.
 *
 * Здесь НЕТ смысла «связь есть» или «всё хорошо». Полоса означает ровно одно:
 * работа идёт. Ошибку она не показывает — для ошибки есть текст ошибки.
 */
object Busy {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val count = MutableStateFlow(0)

    val active: StateFlow<Boolean> =
        count.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Пометить работу на время блока.
     *
     * `finally` обязателен: операция может упасть или быть отменена (человек
     * ушёл с экрана), и полоса, оставшаяся бежать навсегда, — это ровно тот
     * контрол, который врёт.
     */
    suspend fun <T> during(block: suspend () -> T): T {
        count.value = count.value + 1
        try {
            return block()
        } finally {
            count.value = (count.value - 1).coerceAtLeast(0)
        }
    }
}
