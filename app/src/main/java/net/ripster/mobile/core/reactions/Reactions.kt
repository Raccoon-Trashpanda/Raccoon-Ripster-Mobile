package net.ripster.mobile.core.reactions

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Что человек показал станции: скипнул рано, скипнул поздно, лайкнул, дослушал,
 * скачал.
 *
 * `DOWNLOAD` в списке намеренно: спецификация Сола считает скачивание слабым
 * плюсом, и когда он появится, его надо будет отличить от лайка, а не смешивать
 * с `FULL`.
 */
enum class ReactionKind { EARLY_SKIP, LATE_SKIP, LIKE, FULL, DOWNLOAD }

/**
 * Откуда реакция. Сейчас пишется только [PHONE]; [PC] заведён заранее, потому что
 * импорт реакций с ПК — тот же буфер, и смешивать «моё» и «принесённое» придётся
 * уже с готовым полем, а не задним числом.
 */
enum class ReactionOrigin { PHONE, PC }

/**
 * Одна реакция.
 *
 * Хранится кольцевым буфером последних [Reactions.CAP] записей в существующем
 * хранилище настроек (JSON-строкой), а НЕ в Room: схему `play_history` править
 * нельзя (020 п.2.1), а ей и незачем — станции нужно различие «скипнул через 5
 * секунд» и «дослушал», чего в журнале прослушивания нет вовсе.
 */
data class Reaction(
    val artist: String,
    val kind: ReactionKind,
    /** Метка времени в миллисекундах — те же часы, что и `playedAt` журнала. */
    val at: Long,
    val album: String? = null,
    val origin: ReactionOrigin = ReactionOrigin.PHONE,
    /**
     * Где именно оборвали трек и какова его длительность. Нужны для штрафа:
     * спецификация считает «ранний скип» до `min(15 с, 10% длительности)`,
     * «поздний» — после 50%, а для промежутка требует ПЛАВНОГО перехода, которого
     * по одному слову `kind` не выразишь. Поля необязательные: реакция, пришедшая
     * без них (например, импорт с ПК), получает плоский штраф по `kind`.
     */
    val positionMs: Long? = null,
    val durationMs: Long? = null,
)

object Reactions {

    /** Сколько храним. Дальше самое старое вытесняется. */
    const val CAP = 300

    /** Тот же файл настроек, что у `AppSettings`: отдельное хранилище не заводим. */
    internal const val PREFS_NAME = "ripster_settings"
    internal const val KEY = "station-reactions"

    /** Прибавить реакцию, держа буфер кольцевым: старшее уходит первым. */
    fun add(items: List<Reaction>, reaction: Reaction): List<Reaction> =
        (items + reaction).takeLast(CAP)

    /** Прочитать буфер. Пусто — законное «не знаю», а не «всё нравилось». */
    fun load(context: Context): List<Reaction> =
        Codec.decode(prefs(context).getString(KEY, null))

    /** Записать буфер целиком. */
    fun save(context: Context, items: List<Reaction>) {
        prefs(context).edit().putString(KEY, Codec.encode(items)).apply()
    }

    /** Одна реакция в хранилище: прочитать → [add] → записать. */
    fun append(context: Context, reaction: Reaction): List<Reaction> {
        val next = add(load(context), reaction)
        save(context, next)
        return next
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Кодировщик. Руками на `JsonElement`, а не через `@Serializable`, по той же
     * причине, что и `RecentQueries.Codec`: строка может прийти от любой прошлой
     * версии, и ронять из-за неё буфер нельзя — молча потерять один раз лучше,
     * чем упасть на глазах у всего приложения.
     */
    object Codec {

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(items: List<Reaction>): String = buildJsonArray {
            items.forEach { r ->
                add(
                    JsonObject(
                        buildMap {
                            put("artist", JsonPrimitive(r.artist))
                            put("kind", JsonPrimitive(r.kind.name))
                            put("at", JsonPrimitive(r.at))
                            r.album?.let { put("album", JsonPrimitive(it)) }
                            put("origin", JsonPrimitive(r.origin.name))
                            // Позиция и длительность — не деталь UI: без них
                            // «ранний/поздний» превращается в догадку, а штраф
                            // должен зависеть от того, где именно оборвали.
                            r.positionMs?.let { put("position", JsonPrimitive(it)) }
                            r.durationMs?.let { put("duration", JsonPrimitive(it)) }
                        },
                    ),
                )
            }
        }.toString()

        /** Мусор, незнакомое поле, не-массив — пустой список. Исключение наружу не летит. */
        fun decode(raw: String?): List<Reaction> {
            if (raw.isNullOrBlank()) return emptyList()
            val root = try {
                json.parseToJsonElement(raw)
            } catch (_: SerializationException) {
                // Разбором строки занимается kotlinx; отмене тут взяться негде
                // (вызов не суспендирующий), поэтому ловим именно ошибку
                // сериализации, а не `Exception` вообще.
                return emptyList()
            }
            if (root !is JsonArray) return emptyList()
            return root.mapNotNull { el -> read(el) }
        }

        private fun read(el: Any?): Reaction? {
            val o = el as? JsonObject ?: return null
            val artist = o.str("artist") ?: return null
            val kind = ReactionKind.entries.firstOrNull { it.name == o.str("kind") } ?: return null
            val at = o.num("at") ?: return null
            return Reaction(
                artist = artist,
                kind = kind,
                at = at,
                album = o.str("album"),
                origin = ReactionOrigin.entries.firstOrNull { it.name == o.str("origin") }
                    ?: ReactionOrigin.PHONE,
                positionMs = o.num("position"),
                durationMs = o.num("duration"),
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }

        private fun JsonObject.num(key: String): Long? =
            (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
    }
}
