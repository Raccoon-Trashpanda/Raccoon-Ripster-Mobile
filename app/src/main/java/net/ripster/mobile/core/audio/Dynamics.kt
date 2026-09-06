package net.ripster.mobile.core.audio

/**
 * Что сделали с записью при мастеринге.
 *
 * Живёт отдельно от [Spectrogram] намеренно: там объект тянет за собой
 * Android-графику, и обычный тест на нём падает ещё до первой строки. Чистая
 * арифметика не должна быть заложницей рисования — иначе её нельзя проверить,
 * а непроверенное измерение хуже отсутствующего: оно выглядит как факт.
 */
object Dynamics {

/** Что сделали с записью при мастеринге: пик, размах, срезанные пики. */
data class Result(val peakDb: Float, val crestDb: Float, val clipped: Int)

/**
 * Считается по уже разобранному PCM, поэтому обходится в один проход и
 * ничего не декодирует заново.
 *
 * Экран «Подробности потока» говорил, ЧЕМ файл закодирован и не подделка ли
 * это lossless (срез, кирпичная стена), но молчал о самой музыке. Человеку,
 * который выбирает между двумя изданиями одного альбома, важно именно это.
 */
fun of(pcm: FloatArray): Result {
    var peak = 0f
    var sumSq = 0.0
    var clipped = 0
    for (v in pcm) {
        val a = kotlin.math.abs(v)
        if (a > peak) peak = a
        sumSq += v.toDouble() * v
        // 0.999, а не 1.0: после декодирования ровная единица почти не
        // встречается, а срезанный пик садится вплотную к ней.
        if (a >= 0.999f) clipped++
    }
    val rms = if (pcm.isNotEmpty()) kotlin.math.sqrt(sumSq / pcm.size).toFloat() else 0f
    fun db(x: Float) = if (x > 1e-7f) (20.0 * kotlin.math.log10(x.toDouble())).toFloat() else -120f
    // Тишина — это отсутствие данных, а не «нулевая динамика»: пустой файл
    // не должен приезжать человеку как зажатый мастеринг.
    val crest = if (peak > 1e-7f && rms > 1e-7f) db(peak) - db(rms) else 0f
    return Result(db(peak), crest, clipped)
}
}
