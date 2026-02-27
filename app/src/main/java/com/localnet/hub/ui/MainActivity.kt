package com.localnet.hub.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.localnet.hub.R
import com.localnet.hub.wifi.WifiDirectManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var wifiDirect: WifiDirectManager
    private lateinit var messageAdapter: MessageAdapter

    // Views
    private lateinit var tabLayout: TabLayout
    private lateinit var contentServer: View
    private lateinit var contentP2p: View

    // Server tab views
    private lateinit var tvServerStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var btnCopyUrl: Button
    private lateinit var rvMessages: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var tvClientCount: TextView

    // P2P tab views
    private lateinit var tvP2pStatus: TextView
    private lateinit var tvGroupInfo: TextView
    private lateinit var btnCreateGroup: Button
    private lateinit var btnRemoveGroup: Button
    private lateinit var btnDiscover: Button
    private lateinit var lvPeers: ListView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions denied — features may be limited", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        wifiDirect = WifiDirectManager(this)

        bindViews()
        setupTabs()
        setupRecyclerView()
        setupListeners()
        checkPermissions()
        observeState()
    }

    private fun bindViews() {
        tabLayout = findViewById(R.id.tabLayout)
        contentServer = findViewById(R.id.contentServer)
        contentP2p = findViewById(R.id.contentP2p)

        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        rvMessages = findViewById(R.id.rvMessages)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        tvClientCount = findViewById(R.id.tvClientCount)

        tvP2pStatus = findViewById(R.id.tvP2pStatus)
        tvGroupInfo = findViewById(R.id.tvGroupInfo)
        btnCreateGroup = findViewById(R.id.btnCreateGroup)
        btnRemoveGroup = findViewById(R.id.btnRemoveGroup)
        btnDiscover = findViewById(R.id.btnDiscover)
        lvPeers = findViewById(R.id.lvPeers)
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Server"))
        tabLayout.addTab(tabLayout.newTab().setText("Wi-Fi Direct"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                contentServer.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                contentP2p.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        rvMessages.adapter = messageAdapter
        rvMessages.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
    }

    private fun setupListeners() {
        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.sendMessageFromHost("Host (this device)", text)
                etMessage.setText("")
            }
        }

        btnCopyUrl.setOnClickListener {
            val url = viewModel.uiState.value.serverUrl
            if (url.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Server URL", url))
                Snackbar.make(btnCopyUrl, "URL copied to clipboard", Snackbar.LENGTH_SHORT).show()
            }
        }

        btnCreateGroup.setOnClickListener {
            wifiDirect.createGroup { success, msg ->
                runOnUiThread { viewModel.showSnack(msg) }
            }
        }

        btnRemoveGroup.setOnClickListener {
            wifiDirect.removeGroup { success, msg ->
                runOnUiThread { viewModel.showSnack(msg) }
            }
        }

        btnDiscover.setOnClickListener {
            wifiDirect.discoverPeers { success, msg ->
                runOnUiThread { viewModel.showSnack(msg) }
            }
        }

        lvPeers.setOnItemClickListener { _, _, position, _ ->
            val p2p = viewModel.uiState.value.p2pState
            if (position < p2p.peers.size) {
                val device = p2p.peers[position]
                wifiDirect.connectToPeer(device) { success, msg ->
                    runOnUiThread { viewModel.showSnack(msg) }
                }
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                // Server tab
                tvServerStatus.text = if (state.serverRunning) "● Server Running" else "○ Server Stopped"
                tvServerStatus.setTextColor(
                    if (state.serverRunning) 0xFF4CAF50.toInt() else 0xFFf44336.toInt()
                )
                tvIpAddress.text = "IP: ${state.localIp}"
                tvServerUrl.text = state.serverUrl.ifEmpty { "Server not reachable" }
                tvClientCount.text = "${state.clients.size} device(s) connected"

                messageAdapter.updateMessages(state.messages)
                if (state.messages.isNotEmpty()) {
                    rvMessages.scrollToPosition(state.messages.size - 1)
                }

                // P2P tab
                val p2p = state.p2pState
                tvP2pStatus.text = p2p.statusMessage
                tvGroupInfo.text = if (p2p.groupFormed) {
                    if (p2p.isGroupOwner) {
                        "SSID: ${p2p.groupSsid}\nPassword: ${p2p.groupPassword}\nClients: ${p2p.connectedDevices.size}"
                    } else {
                        "Connected to group\nOwner: ${p2p.groupOwnerAddress}"
                    }
                } else {
                    "No group active"
                }

                val peerNames = p2p.peers.map { "${it.deviceName ?: it.deviceAddress} (tap to connect)" }
                lvPeers.adapter = ArrayAdapter(this@MainActivity,
                    android.R.layout.simple_list_item_1, peerNames)

                if (state.snackMessage.isNotEmpty()) {
                    Snackbar.make(tabLayout, state.snackMessage, Snackbar.LENGTH_SHORT).show()
                    viewModel.clearSnack()
                }

                // Update P2P state from manager
                viewModel.updateP2pState(wifiDirect.state.value)
            }
        }

        lifecycleScope.launch {
            wifiDirect.state.collect { p2pState ->
                viewModel.updateP2pState(p2pState)
            }
        }
    }

    private fun checkPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        wifiDirect.registerReceiver()
        wifiDirect.requestGroupInfo()
    }

    override fun onPause() {
        super.onPause()
        wifiDirect.unregisterReceiver()
    }
}
