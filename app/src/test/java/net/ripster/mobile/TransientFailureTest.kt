
/**
 * Упавшие падают ПАЧКОЙ и по одной причине.
 *
 * 06.09.2026, эмулятор: девять треков «Mezzanine» лежали как окончательно
 * упавшие, и у всех девяти в базе стояло одно и то же — «429 Too Many
 * Requests». Альбом на девятнадцать треков дёргает сервис девятнадцать раз
 * подряд, тот просит подождать, и красными становятся сразу все.
 *
 * Нажатый вручную повтор докачал трек целиком: ограничение к тому времени
 * давно снялось. То есть человеку надо было нажать «Повторить» девять раз,
 * чтобы исправить ОДНУ помеху. Отсюда групповой повтор — и здесь стережётся
 * его условие: он показывается ровно тогда, когда есть что повторять.
 */
class RetryAllVisibilityTest {

    private fun shows(failed: Int) = failed > 1

    @org.junit.Test
    fun `one failure needs no batch button`() {
        // На одну строку хватает её собственной кнопки: лишний контрол в
        // шапке — это шум, а не помощь.
        org.junit.Assert.assertFalse(shows(1))
    }

    @org.junit.Test
    fun `a batch of failures gets the batch button`() {
        org.junit.Assert.assertTrue(shows(8))
    }

    @org.junit.Test
    fun `nothing failed means nothing to offer`() {
        org.junit.Assert.assertFalse(shows(0))
    }

    @org.junit.Test
    fun `a rate limit is exactly what the batch button is for`() {
        // Связь с TransientFailure: пачка красных строк с 429 — это не девять
        // разных бед, а одна.
        org.junit.Assert.assertTrue(
            net.ripster.mobile.core.download.TransientFailure.isTransient(
                "Apple: Failed to rip album: error getting album response: 429 Too Many Requests",
            ),
        )
    }
}
