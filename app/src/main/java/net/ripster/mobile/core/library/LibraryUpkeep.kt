package net.ripster.mobile.core.library

import net.ripster.mobile.core.db.LibraryEntity
import java.security.MessageDigest

/**
 * Библиотека описывает файлы на диске. Значит, у неё две обязанности, о которых
 * до сих пор никто не заботился: не заводить одну и ту же вещь дважды и не
 * держать записи о том, чего больше нет.
 *
 * Что нашлось на телефоне владельца 05.09.2026 (28 строк в базе):
 *
 *  * «Shattered Memories» — ТРИ строки на один и тот же файл, причём файл лежал
 *    в кэше `/data/user/0/.../cache/qb_255933316.flac`, который ОС давно
 *    вычистила. Три записи, ни одна не играет;
 *  * «Teardrop» и ещё один трек — по две строки на один путь.
 *
 * Причина одна: идентификатором записи был id ЗАДАЧИ ЗАГРУЗКИ — свежий UUID на
 * каждое скачивание. Один и тот же файл, скачанный дважды, давал две строки, и
 * `upsert` ничего не перезаписывал. Теперь ключ — сам файл, как это с самого
 * начала сделано у импорта своей папки ([FolderImport]).
 *
 * Решение вынесено в чистую функцию [plan] нарочно: проверять «когда стирать
 * запись» на живом телефоне слишком дорого, а ошибка здесь стирает фонотеку.
 */
object LibraryUpkeep {

    /** Устойчивый идентификатор записи — по адресу файла, а не по задаче. */
    fun idFor(path: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
        return "lib:" + d.take(12).joinToString("") { "%02x".format(it) }
    }

    data class Plan(
        /** Пути, все записи по которым надо стереть. */
        val forget: List<String> = emptyList(),
        /** Что завести заново — ровно одна запись на путь, с устойчивым id. */
        val keep: List<LibraryEntity> = emptyList(),
        /** Сколько путей имели БОЛЬШЕ одной записи. Считается отдельно от
         *  перевыдачи ключей: «склеили дубли» и «переписали ключ» — разные
         *  события, и в одном числе они лгут о масштабе. */
        val duplicates: Int = 0,
    ) {
        /** Записей о файлах, которых больше нет. */
        val ghosts: Int get() = forget.size - keep.size
    }

    /**
     * Что сделать с библиотекой.
     *
     * [exists] отвечает про файл: `true` — есть, `false` — точно нет,
     * `null` — ПРОВЕРИТЬ НЕ УДАЛОСЬ. Третий ответ обязателен: у записи может
     * быть SAF-адрес, доступ к которому сейчас не выдан, и трактовать «не
     * смог спросить» как «файла нет» — это стереть чужую фонотеку по ошибке.
     * Такие записи не трогаем вовсе.
     */
    fun plan(rows: List<LibraryEntity>, exists: (String) -> Boolean?): Plan {
        val forget = mutableListOf<String>()
        val keep = mutableListOf<LibraryEntity>()
        var duplicates = 0

        for ((path, group) in rows.groupBy { it.filePath }) {
            when (exists(path)) {
                false -> {
                    // Файла нет — записи о нём тоже быть не должно: тап по ней
                    // даёт тишину, и это худший вид неправды в фонотеке.
                    forget += path
                }
                null -> Unit          // не смогли спросить — не наше дело
                true -> {
                    val wanted = idFor(path)
                    val newest = group.maxByOrNull { it.addedAt } ?: continue
                    // Одна запись с правильным ключом — трогать нечего.
                    if (group.size == 1 && newest.id == wanted) continue
                    if (group.size > 1) duplicates++
                    forget += path
                    keep += newest.copy(id = wanted)
                }
            }
        }
        return Plan(forget, keep, duplicates)
    }
}
