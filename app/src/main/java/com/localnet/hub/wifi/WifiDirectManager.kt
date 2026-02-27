package com.localnet.hub.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class P2pState(
    val isEnabled: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupFormed: Boolean = false,
    val groupOwnerAddress: String = "",
    val groupSsid: String = "",
    val groupPassword: String = "",
    val peers: List<WifiP2pDevice> = emptyList(),
    val connectedDevices: List<WifiP2pDevice> = emptyList(),
    val statusMessage: String = "Initializing..."
)

class WifiDirectManager(private val context: Context) {

    private val TAG = "WifiDirectManager"
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, context.mainLooper, null)

    private val _state = MutableStateFlow(P2pState())
    val state: StateFlow<P2pState> = _state

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.value = _state.value.copy(
                        isEnabled = enabled,
                        statusMessage = if (enabled) "Wi-Fi Direct ready" else "Wi-Fi Direct disabled"
                    )
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager?.requestPeers(channel) { peerList ->
                        _state.value = _state.value.copy(peers = peerList.deviceList.toList())
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager?.requestGroupInfo(channel) { group ->
                        if (group != null) {
                            _state.value = _state.value.copy(
                                groupFormed = true,
                                isGroupOwner = group.isGroupOwner,
                                groupSsid = group.networkName ?: "",
                                groupPassword = group.passphrase ?: "",
                                groupOwnerAddress = group.owner?.deviceAddress ?: "",
                                connectedDevices = group.clientList.toList(),
                                statusMessage = if (group.isGroupOwner)
                                    "Hosting group: ${group.networkName}"
                                else
                                    "Connected to: ${group.owner?.deviceName}"
                            )
                        } else {
                            _state.value = _state.value.copy(
                                groupFormed = false,
                                isGroupOwner = false,
                                statusMessage = "No group formed"
                            )
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Log.d(TAG, "This device changed")
                }
            }
        }
    }

    val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    fun registerReceiver() = context.registerReceiver(receiver, intentFilter)
    fun unregisterReceiver() = try { context.unregisterReceiver(receiver) } catch (_: Exception) {}

    fun discoverPeers(onResult: (Boolean, String) -> Unit) {
        if (manager == null || channel == null) {
            onResult(false, "Wi-Fi Direct not available")
            return
        }
        _state.value = _state.value.copy(statusMessage = "Discovering peers...")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true, "Discovery started")
            override fun onFailure(reason: Int) =
                onResult(false, "Discovery failed: ${reasonString(reason)}")
        })
    }

    fun createGroup(onResult: (Boolean, String) -> Unit) {
        if (manager == null || channel == null) {
            onResult(false, "Wi-Fi Direct not available")
            return
        }
        _state.value = _state.value.copy(statusMessage = "Creating group...")
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true, "Group creation initiated")
            override fun onFailure(reason: Int) =
                onResult(false, "Group creation failed: ${reasonString(reason)}")
        })
    }

    fun removeGroup(onResult: (Boolean, String) -> Unit) {
        if (manager == null || channel == null) {
            onResult(false, "Wi-Fi Direct not available")
            return
        }
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(
                    groupFormed = false,
                    isGroupOwner = false,
                    statusMessage = "Group removed"
                )
                onResult(true, "Group removed")
            }
            override fun onFailure(reason: Int) =
                onResult(false, "Remove failed: ${reasonString(reason)}")
        })
    }

    fun connectToPeer(device: WifiP2pDevice, onResult: (Boolean, String) -> Unit) {
        if (manager == null || channel == null) {
            onResult(false, "Wi-Fi Direct not available")
            return
        }
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        _state.value = _state.value.copy(statusMessage = "Connecting to ${device.deviceName}...")
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true, "Connecting...")
            override fun onFailure(reason: Int) =
                onResult(false, "Connection failed: ${reasonString(reason)}")
        })
    }

    fun requestGroupInfo() {
        manager?.requestGroupInfo(channel) { group ->
            if (group != null) {
                _state.value = _state.value.copy(
                    groupFormed = true,
                    isGroupOwner = group.isGroupOwner,
                    groupSsid = group.networkName ?: "",
                    groupPassword = group.passphrase ?: "",
                    connectedDevices = group.clientList.toList()
                )
            }
        }
    }

    private fun reasonString(reason: Int) = when (reason) {
        WifiP2pManager.ERROR -> "Internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.BUSY -> "System busy"
        else -> "Unknown ($reason)"
    }
}
