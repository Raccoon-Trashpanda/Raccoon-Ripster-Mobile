package net.ripster.mobile

import net.ripster.mobile.core.audio.Dynamics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что сделали с записью при мастеринге.
 *
 * Экран «Подробности потока» показывал, ЧЕМ файл закодирован и не подделка ли
 * это lossless (срез спектра, кирпичная стена), но о самой музыке молчал. А
 * человеку, который выбирает между двумя изданиями одного альбома, важно
 * ровно это: зажали её «под громкость» или оставили дышать.
 *
 * Числа здесь названы честно. Крест-фактор — не официальный DR (TT-DR), тот
 * считается иначе; выдавать одно за другое значило бы соврать точной цифрой.
 */
class DynamicsTest {

    private fun sine(n: Int, amp: Float): FloatArray =
        FloatArray(n) { amp * kotlin.math.sin(2.0 * Math.PI * it / 64.0).toFloat() }

    @Test
    fun `a sine has the crest factor a sine must have`() {
        // Пик выше RMS ровно в корень из двух: 3.01 дБ. Если эта цифра уедет —
        // уехал сам счёт, а не музыка.
        val d = Dynamics.of(sine(6400, 0.5f))
        assertEquals(3.01f, d.crestDb, 0.05f)
        assertEquals(-6.02f, d.peakDb, 0.05f)
        assertEquals(0, d.clipped)
    }

    @Test
    fun `squashed loud material shows a small crest factor`() {
        // Меандр на полной громкости: пик равен среднему, размаха нет.
        val pcm = FloatArray(4000) { if (it % 2 == 0) 0.98f else -0.98f }
        assertTrue("зажатое должно давать малый размах", Dynamics.of(pcm).crestDb < 0.5f)
    }

    @Test
    fun `peaks driven into the ceiling are counted`() {
        val pcm = FloatArray(1000) { if (it < 37) 1.0f else 0.2f }
        assertEquals(37, Dynamics.of(pcm).clipped)
    }

    @Test
    fun `silence is missing data, not zero dynamics`() {
        // Пустой и тихий файл не должны приезжать человеку как «зажатый
        // мастеринг»: это разные вещи, и путать их — врать.
        val d = Dynamics.of(FloatArray(0))
        assertEquals(0f, d.crestDb, 0.001f)
        assertEquals(0, d.clipped)
        assertEquals(0f, Dynamics.of(FloatArray(500)).crestDb, 0.001f)
    }
}
