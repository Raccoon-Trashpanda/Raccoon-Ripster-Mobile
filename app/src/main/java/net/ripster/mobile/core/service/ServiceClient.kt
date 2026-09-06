package net.ripster.mobile.core.service

import kotlinx.coroutines.flow.Flow
import net.ripster.mobile.core.model.DownloadEvent
import net.ripster.mobile.core.model.DownloadRequest
import net.ripster.mobile.core.model.MediaSelection
import net.ripster.mobile.core.model.QualityTier
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.StreamInfo
import net.ripster.mobile.core.model.Track

/**
 * Один сервис = одна реализация. Аналог `EngineBase` на десктопе: там
 * `iter_events()` — async-генератор событий, здесь [download] — `Flow`.
 *
 * Клиент НЕ трогает файловую систему и не пишет теги: он доводит дело до
 * готового потока байт нужного качества, дальше общий загрузчик пишет файл в
 * выбранную папку (SAF) и проставляет теги. Так расшифровка и протокол
 * сервиса не размазаны по слою хранения.
 */
interface ServiceClient {

    val service: Service

    /** Может ли этот клиент вообще работать сейчас (есть токен/логин). */
    suspend fun isConfigured(): Boolean

    /**
     * Прогреть авторизацию заранее (в фоне при регистрации клиентов), чтобы
     * ПЕРВЫЙ поиск не платил за логин/скрейп внутри своего 15-секундного
     * таймаута и не падал «сервис не ответил». No-op по умолчанию — переопределяют
     * только клиенты с дорогим входом (Qobuz скрейпит bundle.js).
     */
    suspend fun warmUp() {}

    /** Свободный текстовый поиск. */
    suspend fun search(query: String): MediaSelection

    /** Разобрать ссылку сервиса (трек/альбом/плейлист/артист). null — ссылка не наша. */
    suspend fun resolve(url: String): MediaSelection?

    /**
     * Дискография артиста по его id в этом сервисе — БЕЗ ПК. Свои релизы плюс
     * секция «с этим артистом» (компиляции/миксы, куда попал его трек):
     * помечаются `type = "compilation"` + `albumArtist` (чей релиз) +
     * `appearsAs` (какой трек артиста). null — клиент так не умеет.
     */
    suspend fun getArtist(artistId: String): net.ripster.mobile.core.pair.PcBridge.ArtistPage? = null

    /** Какие уровни качества доступны этому аккаунту, в порядке убывания. */
    suspend fun qualities(): List<QualityTier>

    /**
     * Какие качества есть у КОНКРЕТНОГО трека, в порядке убывания.
     *
     * По умолчанию — то же, что [qualities]: у большинства сервисов набор
     * определяется подпиской, а не записью. У SoundCloud это не так: там
     * набор потоков свой у каждого трека, и предлагать общий список значит
     * предлагать то, чего у этой записи нет.
     *
     * Пустой список = «спросить не удалось», а не «качеств нет»: вызывающий
     * в этом случае показывает общий список, а не пустой экран.
     */
    suspend fun qualitiesFor(track: Track): List<QualityTier> = qualities()

    /**
     * Выбрать конкретный поток для трека. `preference` — id-шники [QualityTier]
     * по убыванию желаемого; клиент берёт первый доступный.
     */
    suspend fun streamInfo(track: Track, preference: List<String>): StreamInfo

    /**
     * Полный цикл одного трека: поток → (расшифровка) → временный файл →
     * события прогресса. Финальный [DownloadEvent.Done] несёт путь к готовому
     * файлу БЕЗ тегов и БЕЗ переноса в пользовательскую папку — это делает
     * вызывающий.
     */
    fun download(request: DownloadRequest): Flow<DownloadEvent>
}
