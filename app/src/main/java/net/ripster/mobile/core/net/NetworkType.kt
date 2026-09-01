package net.ripster.mobile.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Тип текущей сети — чтобы выбирать качество загрузки как в Apple Music:
 * по Wi-Fi одно предпочтение, по мобильной сети другое.
 */
object NetworkType {

    /** true, если активная сеть — Wi-Fi/Ethernet (не считается лимитной). */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
