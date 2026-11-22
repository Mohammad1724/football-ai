package com.footballai.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone currently has a working internet connection.
 *
 * The app learns that a source is down by trying it, but it must not start a sync — or
 * show a spinner that can never finish — when the device already knows it is offline.
 * A failed request also costs nothing in quota while the user sees a clear "no internet"
 * message instead of a generic failure.
 *
 * The value starts as "online" because the very first callback arrives a moment after
 * start-up; blocking the first request on it would make a cold start feel broken.
 */
class NetworkMonitor(context: Context) {

    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(connectivity.currentlyOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        val manager = connectivity
        if (manager != null) {
            runCatching {
                manager.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            _isOnline.value = true
                        }

                        override fun onLost(network: Network) {
                            // A different network may still be up (wifi dropped, mobile on).
                            _isOnline.value = manager.currentlyOnline()
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            _isOnline.value =
                                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        }
                    },
                )
            }
        }
    }

    /** Synchronous check for callers that cannot wait for the flow. */
    fun isOnlineNow(): Boolean = connectivity.currentlyOnline().also { _isOnline.value = it }

    private fun ConnectivityManager?.currentlyOnline(): Boolean {
        val manager = this ?: return true
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
