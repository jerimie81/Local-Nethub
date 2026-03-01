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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private lateinit var contentSettings: View

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

    // Settings tab views
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var switchMobileFallback: SwitchMaterial
    private lateinit var switchDarkMode: SwitchMaterial
    private lateinit var etDeviceAlias: EditText
    private lateinit var switchRequirePin: SwitchMaterial
    private lateinit var switchSshOnly: SwitchMaterial
    private lateinit var spinnerVisibility: Spinner
    private lateinit var switchNotifyDevice: SwitchMaterial
    private lateinit var switchNotifyErrors: SwitchMaterial
    private lateinit var switchEnableLogging: SwitchMaterial
    private lateinit var spinnerLogLevel: Spinner
    private lateinit var spinnerLogDestination: Spinner
    private lateinit var btnApplySettings: Button

    private val settingsPrefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

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
        applySavedThemeMode()
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
        contentSettings = findViewById(R.id.contentSettings)

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

        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchMobileFallback = findViewById(R.id.switchMobileFallback)
        switchDarkMode = findViewById(R.id.switchDarkMode)
        etDeviceAlias = findViewById(R.id.etDeviceAlias)
        switchRequirePin = findViewById(R.id.switchRequirePin)
        switchSshOnly = findViewById(R.id.switchSshOnly)
        spinnerVisibility = findViewById(R.id.spinnerVisibility)
        switchNotifyDevice = findViewById(R.id.switchNotifyDevice)
        switchNotifyErrors = findViewById(R.id.switchNotifyErrors)
        switchEnableLogging = findViewById(R.id.switchEnableLogging)
        spinnerLogLevel = findViewById(R.id.spinnerLogLevel)
        spinnerLogDestination = findViewById(R.id.spinnerLogDestination)
        btnApplySettings = findViewById(R.id.btnApplySettings)
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.tab_server)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.tab_wifi_direct)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.tab_settings)))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                contentServer.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                contentP2p.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                contentSettings.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
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
        setupSettingsControls()

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
            wifiDirect.createGroup { _, msg ->
                runOnUiThread { viewModel.showSnack(msg) }
            }
        }

        btnRemoveGroup.setOnClickListener {
            wifiDirect.removeGroup { _, msg ->
                runOnUiThread { viewModel.showSnack(msg) }
            }
        }

        btnDiscover.setOnClickListener {
            wifiDirect.discoverPeers { _, msg ->
                runOnUiThread { viewModel.showSnack(msg) }
            }
        }

        lvPeers.setOnItemClickListener { _, _, position, _ ->
            val p2p = viewModel.uiState.value.p2pState
            if (position < p2p.peers.size) {
                val device = p2p.peers[position]
                wifiDirect.connectToPeer(device) { _, msg ->
                    runOnUiThread { viewModel.showSnack(msg) }
                }
            }
        }
    }

    private fun setupSettingsControls() {
        val visibilityOptions = listOf(
            getString(R.string.visibility_private),
            getString(R.string.visibility_team),
            getString(R.string.visibility_public)
        )
        spinnerVisibility.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            visibilityOptions
        )

        val logLevelOptions = listOf(
            getString(R.string.log_level_error),
            getString(R.string.log_level_warn),
            getString(R.string.log_level_info),
            getString(R.string.log_level_debug)
        )
        spinnerLogLevel.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            logLevelOptions
        )

        val logDestinationOptions = listOf(
            getString(R.string.log_destination_app),
            getString(R.string.log_destination_downloads),
            getString(R.string.log_destination_shared)
        )
        spinnerLogDestination.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            logDestinationOptions
        )

        switchAutoStart.isChecked = settingsPrefs.getBoolean(KEY_AUTO_START, true)
        switchMobileFallback.isChecked = settingsPrefs.getBoolean(KEY_MOBILE_FALLBACK, false)
        switchDarkMode.isChecked = settingsPrefs.getBoolean(KEY_DARK_MODE, false)
        etDeviceAlias.setText(settingsPrefs.getString(KEY_DEVICE_ALIAS, ""))
        switchRequirePin.isChecked = settingsPrefs.getBoolean(KEY_REQUIRE_PIN, false)
        switchSshOnly.isChecked = settingsPrefs.getBoolean(KEY_SSH_ONLY, false)
        spinnerVisibility.setSelection(settingsPrefs.getInt(KEY_VISIBILITY_INDEX, 1).coerceIn(0, 2))
        switchNotifyDevice.isChecked = settingsPrefs.getBoolean(KEY_NOTIFY_DEVICE, true)
        switchNotifyErrors.isChecked = settingsPrefs.getBoolean(KEY_NOTIFY_ERRORS, true)
        switchEnableLogging.isChecked = settingsPrefs.getBoolean(KEY_ENABLE_LOGGING, true)
        spinnerLogLevel.setSelection(settingsPrefs.getInt(KEY_LOG_LEVEL_INDEX, 2).coerceIn(0, 3))
        spinnerLogDestination.setSelection(settingsPrefs.getInt(KEY_LOG_DEST_INDEX, 0).coerceIn(0, 2))

        btnApplySettings.setOnClickListener {
            val alias = etDeviceAlias.text.toString().trim().ifEmpty { "Current device" }
            val visibility = spinnerVisibility.selectedItem.toString()
            val selectedDarkMode = switchDarkMode.isChecked

            settingsPrefs.edit()
                .putBoolean(KEY_AUTO_START, switchAutoStart.isChecked)
                .putBoolean(KEY_MOBILE_FALLBACK, switchMobileFallback.isChecked)
                .putBoolean(KEY_DARK_MODE, selectedDarkMode)
                .putString(KEY_DEVICE_ALIAS, etDeviceAlias.text.toString().trim())
                .putBoolean(KEY_REQUIRE_PIN, switchRequirePin.isChecked)
                .putBoolean(KEY_SSH_ONLY, switchSshOnly.isChecked)
                .putInt(KEY_VISIBILITY_INDEX, spinnerVisibility.selectedItemPosition)
                .putBoolean(KEY_NOTIFY_DEVICE, switchNotifyDevice.isChecked)
                .putBoolean(KEY_NOTIFY_ERRORS, switchNotifyErrors.isChecked)
                .putBoolean(KEY_ENABLE_LOGGING, switchEnableLogging.isChecked)
                .putInt(KEY_LOG_LEVEL_INDEX, spinnerLogLevel.selectedItemPosition)
                .putInt(KEY_LOG_DEST_INDEX, spinnerLogDestination.selectedItemPosition)
                .apply()

            val mode = if (selectedDarkMode) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
            if (AppCompatDelegate.getDefaultNightMode() != mode) {
                AppCompatDelegate.setDefaultNightMode(mode)
            }

            val summary = buildString {
                append("Settings saved for ")
                append(alias)
                append(" • ")
                append(visibility)
                append(" • ")
                append(spinnerLogLevel.selectedItem.toString())
            }
            Snackbar.make(btnApplySettings, summary, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun applySavedThemeMode() {
        val darkModeEnabled = settingsPrefs.getBoolean(KEY_DARK_MODE, false)
        AppCompatDelegate.setDefaultNightMode(
            if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
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
                lvPeers.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_1,
                    peerNames
                )

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

    companion object {
        private const val PREFS_NAME = "localnet_hub_settings"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_MOBILE_FALLBACK = "mobile_fallback"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_DEVICE_ALIAS = "device_alias"
        private const val KEY_REQUIRE_PIN = "require_pin"
        private const val KEY_SSH_ONLY = "ssh_only"
        private const val KEY_VISIBILITY_INDEX = "visibility_index"
        private const val KEY_NOTIFY_DEVICE = "notify_device"
        private const val KEY_NOTIFY_ERRORS = "notify_errors"
        private const val KEY_ENABLE_LOGGING = "enable_logging"
        private const val KEY_LOG_LEVEL_INDEX = "log_level_index"
        private const val KEY_LOG_DEST_INDEX = "log_dest_index"
    }
}
