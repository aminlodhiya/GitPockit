package dev.gitpockit.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.gitpockit.sync.NetworkStatus

class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun caps(): NetworkCapabilities? = cm.getNetworkCapabilities(cm.activeNetwork)

    override fun isOnline(): Boolean =
        caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun isUnmetered(): Boolean =
        caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
}
