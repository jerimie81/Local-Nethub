package com.localnet.hub.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localnet.hub.server.ChatMessage
import com.localnet.hub.server.ConnectedClient
import com.localnet.hub.server.NetworkService
import com.localnet.hub.wifi.P2pState
import com.localnet.hub.wifi.WifiDirectManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.NetworkInterface

data class UiState(
    val localIp: String = "Detecting...",
    val serverPort: Int = 8080,
    val serverUrl: String = "",
    val serverRunning: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val clients: List<ConnectedClient> = emptyList(),
    val p2pState: P2pState = P2pState(),
    val snackMessage: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    var networkService: NetworkService? = null
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as NetworkService.LocalBinder).getService()
            networkService = service
            service.httpServer.onUpdate = { refreshFromService() }
            _uiState.value = _uiState.value.copy(serverRunning = true)
            refreshFromService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            networkService = null
            _uiState.value = _uiState.value.copy(serverRunning = false)
        }
    }

    init {
        bindToService()
        startPolling()
    }

    private fun bindToService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, NetworkService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                val ip = getLocalIpAddress()
                val url = if (ip != "N/A") "http://$ip:8080" else ""
                _uiState.value = _uiState.value.copy(
                    localIp = ip,
                    serverUrl = url
                )
                refreshFromService()
                delay(2000)
            }
        }
    }

    fun refreshFromService() {
        val svc = networkService ?: return
        _uiState.value = _uiState.value.copy(
            messages = svc.httpServer.messages.toList(),
            clients = svc.httpServer.connectedClients.toList(),
            serverRunning = svc.httpServer.isRunning
        )
    }

    fun sendMessageFromHost(sender: String, content: String) {
        val svc = networkService ?: return
        if (content.isBlank()) return
        svc.httpServer.messages.add(
            com.localnet.hub.server.ChatMessage(sender = sender, content = content)
        )
        if (svc.httpServer.messages.size > 200) svc.httpServer.messages.removeAt(0)
        refreshFromService()
    }

    fun clearMessages() {
        networkService?.httpServer?.messages?.clear()
        refreshFromService()
    }

    fun updateP2pState(state: P2pState) {
        _uiState.value = _uiState.value.copy(p2pState = state)
    }

    fun showSnack(msg: String) {
        _uiState.value = _uiState.value.copy(snackMessage = msg)
    }

    fun clearSnack() {
        _uiState.value = _uiState.value.copy(snackMessage = "")
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    // prefer wlan/p2p addresses
                    if (ip.contains(":")) continue // skip IPv6
                    if (intf.name.startsWith("wlan") || intf.name.startsWith("p2p")) {
                        return ip
                    }
                }
            }
            // fallback: any non-loopback IPv4
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (!ip.contains(":")) return ip
                }
            }
            "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }

    override fun onCleared() {
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (_: Exception) {}
        super.onCleared()
    }
}
