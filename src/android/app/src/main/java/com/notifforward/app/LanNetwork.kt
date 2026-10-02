package com.notifforward.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Prefer a LAN interface even when Android chooses cellular as its default Internet network. */
object LanNetwork {
    fun local(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.allNetworks.firstOrNull { candidate -> manager.getNetworkCapabilities(candidate)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true }
    }
    @Suppress("DEPRECATION")
    fun select(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { candidate ->
            manager.getNetworkCapabilities(candidate)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        } ?: manager.activeNetwork
    }
}
