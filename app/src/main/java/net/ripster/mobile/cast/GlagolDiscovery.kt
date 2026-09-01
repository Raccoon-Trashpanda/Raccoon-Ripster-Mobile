package net.ripster.mobile.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Обнаружение колонок Яндекса в локальной сети через mDNS (`_yandexio._tcp`).
 * Каждая найденная запись несёт `deviceId` (в TXT `deviceId`), имя и, после
 * резолва, ip:port для локального WebSocket-подключения.
 */
class GlagolDiscovery(context: Context) {

    private val appCtx = context.applicationContext
    private val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager

    data class Found(
        val serviceName: String,
        val deviceId: String,
        val host: String,
        val port: Int,
    )

    /** Поток находок. Отписка (закрытие flow) останавливает обнаружение. */
    fun discover(): Flow<Found> = callbackFlow {
        // Без удержанного MulticastLock Android глушит входящий multicast, и
        // mDNS-обнаружение молча ничего не находит — самая частая причина
        // «Рипстер не видит Станцию» на реальном Wi-Fi.
        val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("ripster-glagol")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        val resolveQueue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            val info = resolveQueue.removeFirst()
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    resolving = false; resolveNext()
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val devId = si.attributes["deviceId"]?.let { String(it) } ?: ""
                    val host = si.host?.hostAddress ?: ""
                    if (host.isNotEmpty()) {
                        trySend(Found(si.serviceName ?: "", devId, host, si.port))
                    }
                    resolving = false; resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                resolveQueue.addLast(si); resolveNext()
            }
            override fun onServiceLost(si: NsdServiceInfo?) {}
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock?.takeIf { it.isHeld }?.release() }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_yandexio._tcp."
    }
}
