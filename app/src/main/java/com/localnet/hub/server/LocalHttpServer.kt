package com.localnet.hub.server

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ConnectedClient(
    val id: String = UUID.randomUUID().toString(),
    val ip: String,
    val connectedAt: Long = System.currentTimeMillis()
)

class LocalHttpServer(private val port: Int = 8080) {

    private val TAG = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val messages = CopyOnWriteArrayList<ChatMessage>()
    val connectedClients = CopyOnWriteArrayList<ConnectedClient>()
    var onUpdate: (() -> Unit)? = null
    private val tunnelManager = SshTunnelManager()
    private val keyPairingManager = SshKeyPairingManager()
    private val qrPairingManager = QrKeyPairingManager(keyPairingManager)

    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        isRunning = true
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Server started on port $port")
                while (isActive) {
                    val currentServerSocket = serverSocket ?: break
                    if (currentServerSocket.isClosed) break
                    val socket = currentServerSocket.accept()
                    launch { handleClient(socket) }
                }
            } catch (_: java.net.SocketException) {
                // Expected when stopping the server and closing ServerSocket.
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        serverJob = null
        serverSocket?.close()
        serverSocket = null
        tunnelManager.stopAll()
        Log.i(TAG, "Server stopped")
    }

    private fun handleClient(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "unknown"
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            // Parse request line
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")

            // Read headers
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                val idx = line.indexOf(":")
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                line = reader.readLine()
            }

            // Read body for POST
            var body = ""
            if (method == "POST") {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val bytesRead = reader.read(chars, read, contentLength - read)
                        if (bytesRead <= 0) break
                        read += bytesRead
                    }
                    body = String(chars, 0, read)
                }
            }

            // Track client
            if (connectedClients.none { it.ip == clientIp }) {
                connectedClients.add(ConnectedClient(ip = clientIp))
                onUpdate?.invoke()
            }

            when {
                path == "/" || path == "/index.html" -> {
                    sendResponse(writer, 200, "text/html", buildWebPage())
                }
                path == "/api/messages" && method == "GET" -> {
                    sendResponse(writer, 200, "application/json", messagesToJson())
                }
                path == "/api/send" && method == "POST" -> {
                    val params = parseForm(body)
                    val sender = params["sender"]?.let { URLDecoder.decode(it, "UTF-8") } ?: clientIp
                    val content = params["content"]?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                    if (content.isNotBlank()) {
                        messages.add(ChatMessage(sender = sender, content = content))
                        if (messages.size > 200) messages.removeAt(0)
                        onUpdate?.invoke()
                    }
                    sendResponse(writer, 200, "application/json", """{"status":"ok"}""")
                }
                path == "/api/clients" -> {
                    sendResponse(writer, 200, "application/json", clientsToJson())
                }
                path == "/api/status" -> {
                    sendResponse(writer, 200, "application/json",
                        """{"status":"online","clients":${connectedClients.size},"messages":${messages.size}}""")
                }
                path == "/api/tunnels" && method == "GET" -> {
                    sendResponse(writer, 200, "application/json", tunnelsToJson())
                }
                path == "/api/tunnel/start" && method == "POST" -> {
                    val params = parseForm(body)
                    val targetHost = params["targetHost"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val listenPort = params["listenPort"]?.toIntOrNull() ?: 2222
                    val targetPort = params["targetPort"]?.toIntOrNull() ?: 22
                    val result = tunnelManager.startTunnel(listenPort = listenPort, targetHost = targetHost, targetPort = targetPort)
                    if (result.isSuccess) {
                        onUpdate?.invoke()
                        sendResponse(writer, 200, "application/json", """{"status":"ok","message":"Tunnel started"}""")
                    } else {
                        sendResponse(writer, 400, "application/json", """{"status":"error","message":"${escapeJson(result.exceptionOrNull()?.message ?: "Unknown error")}"}""")
                    }
                }
                path == "/api/tunnel/stop" && method == "POST" -> {
                    val params = parseForm(body)
                    val listenPort = params["listenPort"]?.toIntOrNull()
                    if (listenPort != null && tunnelManager.stopTunnel(listenPort)) {
                        onUpdate?.invoke()
                        sendResponse(writer, 200, "application/json", """{"status":"ok","message":"Tunnel stopped"}""")
                    } else {
                        sendResponse(writer, 404, "application/json", """{"status":"error","message":"Tunnel not found"}""")
                    }
                }
                path == "/api/keys" && method == "GET" -> {
                    sendResponse(writer, 200, "application/json", pairedKeysToJson())
                }
                path == "/api/keys/pair" && method == "POST" -> {
                    val params = parseForm(body)
                    val deviceName = params["deviceName"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val publicKey = params["publicKey"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val result = keyPairingManager.pairKey(deviceName = deviceName, publicKey = publicKey)
                    if (result.isSuccess) {
                        onUpdate?.invoke()
                        val paired = result.getOrThrow()
                        sendResponse(writer, 200, "application/json", """{"status":"ok","id":"${escapeJson(paired.id)}","fingerprint":"${escapeJson(paired.fingerprint)}"}""")
                    } else {
                        sendResponse(writer, 400, "application/json", """{"status":"error","message":"${escapeJson(result.exceptionOrNull()?.message ?: "Pairing failed")}"}""")
                    }
                }
                path == "/api/keys/unpair" && method == "POST" -> {
                    val params = parseForm(body)
                    val id = params["id"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    if (id.isNotBlank() && keyPairingManager.unpairKey(id)) {
                        onUpdate?.invoke()
                        sendResponse(writer, 200, "application/json", """{"status":"ok"}""")
                    } else {
                        sendResponse(writer, 404, "application/json", """{"status":"error","message":"Key not found"}""")
                    }
                }
                path == "/api/pairing/qr/init" && method == "POST" -> {
                    val params = parseForm(body)
                    val deviceName = params["deviceName"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val publicKey = params["publicKey"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val result = qrPairingManager.createInitPayload(deviceName = deviceName, publicKey = publicKey)
                    if (result.isSuccess) {
                        val init = result.getOrThrow()
                        sendResponse(
                            writer,
                            200,
                            "application/json",
                            """{"status":"ok","sessionId":"${escapeJson(init.sessionId)}","sas":"${escapeJson(init.sas)}","expiresAt":${init.expiresAt},"payload":"${escapeJson(init.payload)}"}"""
                        )
                    } else {
                        sendResponse(writer, 400, "application/json", """{"status":"error","message":"${escapeJson(result.exceptionOrNull()?.message ?: "Init failed")}"}""")
                    }
                }
                path == "/api/pairing/qr/respond" && method == "POST" -> {
                    val params = parseForm(body)
                    val initPayload = params["initPayload"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val deviceName = params["deviceName"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val publicKey = params["publicKey"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val result = qrPairingManager.createResponsePayload(
                        initPayload = initPayload,
                        deviceName = deviceName,
                        publicKey = publicKey
                    )
                    if (result.isSuccess) {
                        val response = result.getOrThrow()
                        sendResponse(
                            writer,
                            200,
                            "application/json",
                            """{"status":"ok","sessionId":"${escapeJson(response.sessionId)}","sas":"${escapeJson(response.sas)}","expiresAt":${response.expiresAt},"fingerprint":"${escapeJson(response.responderFingerprint)}","payload":"${escapeJson(response.payload)}"}"""
                        )
                    } else {
                        sendResponse(writer, 400, "application/json", """{"status":"error","message":"${escapeJson(result.exceptionOrNull()?.message ?: "Response failed")}"}""")
                    }
                }
                path == "/api/pairing/qr/finalize" && method == "POST" -> {
                    val params = parseForm(body)
                    val responsePayload = params["responsePayload"]?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val result = qrPairingManager.finalizeFromResponse(responsePayload)
                    if (result.isSuccess) {
                        onUpdate?.invoke()
                        val finalized = result.getOrThrow()
                        sendResponse(
                            writer,
                            200,
                            "application/json",
                            """{"status":"ok","sessionId":"${escapeJson(finalized.sessionId)}","sas":"${escapeJson(finalized.sas)}","id":"${escapeJson(finalized.pairedKey.id)}","fingerprint":"${escapeJson(finalized.pairedKey.fingerprint)}"}"""
                        )
                    } else {
                        sendResponse(writer, 400, "application/json", """{"status":"error","message":"${escapeJson(result.exceptionOrNull()?.message ?: "Finalize failed")}"}""")
                    }
                }
                path == "/style.css" -> {
                    sendResponse(writer, 200, "text/css", buildCss())
                }
                else -> {
                    sendResponse(writer, 404, "text/html", "<h1>404 Not Found</h1>")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Client error [$clientIp]: ${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun sendResponse(writer: PrintWriter, code: Int, contentType: String, body: String) {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "OK" }
        val bytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $code $status\r\n")
        writer.print("Content-Type: $contentType; charset=UTF-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    private fun parseForm(body: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        body.split("&").forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) map[kv[0]] = kv[1]
        }
        return map
    }

    private fun messagesToJson(): String {
        val sb = StringBuilder("[")
        messages.forEachIndexed { i, msg ->
            if (i > 0) sb.append(",")
            sb.append("""{"id":"${msg.id}","sender":"${escapeJson(msg.sender)}","content":"${escapeJson(msg.content)}","time":"${formatTime(msg.timestamp)}"}""")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun clientsToJson(): String {
        val sb = StringBuilder("[")
        connectedClients.forEachIndexed { i, c ->
            if (i > 0) sb.append(",")
            sb.append("""{"ip":"${c.ip}","since":"${formatTime(c.connectedAt)}"}""")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun tunnelsToJson(): String {
        val tunnels = tunnelManager.listTunnels()
        val sb = StringBuilder("[")
        tunnels.forEachIndexed { i, t ->
            if (i > 0) sb.append(",")
            sb.append(
                """{"listenPort":${t.config.listenPort},"targetHost":"${escapeJson(t.config.targetHost)}","targetPort":${t.config.targetPort},"activeConnections":${t.activeConnections},"lastError":"${escapeJson(t.lastError)}"}"""
            )
        }
        sb.append("]")
        return sb.toString()
    }

    private fun pairedKeysToJson(): String {
        val keys = keyPairingManager.listKeys()
        val sb = StringBuilder("[")
        keys.forEachIndexed { i, k ->
            if (i > 0) sb.append(",")
            sb.append(
                """{"id":"${escapeJson(k.id)}","deviceName":"${escapeJson(k.deviceName)}","fingerprint":"${escapeJson(k.fingerprint)}","pairedAt":${k.pairedAt}}"""
            )
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escapeJson(s: String) = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun buildCss() = """
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #0f1117; color: #e0e0e0; height: 100vh; display: flex; flex-direction: column; }
        .header { background: linear-gradient(135deg, #1a1f2e, #252b3b); padding: 16px 20px; border-bottom: 1px solid #2a3040; display: flex; align-items: center; gap: 12px; }
        .header h1 { font-size: 1.2rem; font-weight: 600; color: #64b5f6; }
        .dot { width: 8px; height: 8px; border-radius: 50%; background: #4caf50; animation: pulse 2s infinite; }
        @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }
        .container { flex: 1; display: flex; overflow: hidden; }
        .chat-area { flex: 1; display: flex; flex-direction: column; }
        .messages { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 10px; }
        .msg { background: #1a1f2e; border-radius: 12px; padding: 12px 16px; max-width: 85%; border-left: 3px solid #3d5a80; }
        .msg.own { align-self: flex-end; border-left: none; border-right: 3px solid #64b5f6; background: #1e2840; }
        .msg-header { font-size: 0.75rem; color: #7986cb; margin-bottom: 4px; }
        .msg-body { font-size: 0.95rem; line-height: 1.4; }
        .sidebar { width: 200px; background: #141820; border-left: 1px solid #2a3040; padding: 12px; overflow-y: auto; }
        .sidebar h3 { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 1px; color: #78909c; margin-bottom: 10px; }
        .client-item { font-size: 0.8rem; padding: 6px 8px; background: #1a1f2e; border-radius: 6px; margin-bottom: 6px; color: #81c784; }
        .input-area { padding: 12px 16px; background: #141820; border-top: 1px solid #2a3040; display: flex; gap: 8px; }
        .input-area input { flex: 1; background: #1a1f2e; border: 1px solid #2a3040; border-radius: 8px; padding: 10px 14px; color: #e0e0e0; font-size: 0.9rem; outline: none; }
        .input-area input:focus { border-color: #64b5f6; }
        .input-area button { background: #1565c0; color: #fff; border: none; border-radius: 8px; padding: 10px 18px; cursor: pointer; font-weight: 600; }
        .input-area button:hover { background: #1976d2; }
        .name-row { padding: 8px 16px; background: #0f1117; display: flex; gap: 8px; }
        .name-row input { width: 150px; background: #1a1f2e; border: 1px solid #2a3040; border-radius: 6px; padding: 6px 10px; color: #e0e0e0; font-size: 0.85rem; outline: none; }
        ::-webkit-scrollbar { width: 4px; } ::-webkit-scrollbar-track { background: #0f1117; } ::-webkit-scrollbar-thumb { background: #2a3040; border-radius: 2px; }
    """.trimIndent()

    private fun buildWebPage() = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>LocalNet Hub</title>
<link rel="stylesheet" href="/style.css">
</head>
<body>
<div class="header">
  <div class="dot"></div>
  <h1>LocalNet Hub</h1>
  <span id="status" style="font-size:0.8rem;color:#78909c;margin-left:auto"></span>
</div>
<div class="name-row">
  <label style="font-size:0.8rem;color:#78909c;line-height:30px">Your name:</label>
  <input type="text" id="nameInput" placeholder="Anonymous" maxlength="20">
</div>
<div class="container">
  <div class="chat-area">
    <div class="messages" id="messages"></div>
    <div class="input-area">
      <input type="text" id="msgInput" placeholder="Type a message..." maxlength="500" 
             onkeydown="if(event.key==='Enter') sendMsg()">
      <button onclick="sendMsg()">Send</button>
    </div>
  </div>
  <div class="sidebar">
    <h3>Devices</h3>
    <div id="clients"></div>
  </div>
</div>
<div style="padding:12px 16px;border-top:1px solid #2a3040;background:#111522">
  <h3 style="font-size:0.85rem;color:#90caf9;margin-bottom:8px">Offline SSH Tunnel</h3>
  <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
    <input id="targetHost" placeholder="Target host/IP" style="background:#1a1f2e;border:1px solid #2a3040;border-radius:6px;padding:8px;color:#e0e0e0" />
    <input id="targetPort" type="number" value="22" style="width:90px;background:#1a1f2e;border:1px solid #2a3040;border-radius:6px;padding:8px;color:#e0e0e0" />
    <input id="listenPort" type="number" value="2222" style="width:90px;background:#1a1f2e;border:1px solid #2a3040;border-radius:6px;padding:8px;color:#e0e0e0" />
    <button onclick="startTunnel()" style="background:#2e7d32;color:#fff;border:none;border-radius:6px;padding:8px 12px">Start Tunnel</button>
    <button onclick="stopTunnel()" style="background:#b71c1c;color:#fff;border:none;border-radius:6px;padding:8px 12px">Stop Tunnel</button>
  </div>
  <div id="tunnelStatus" style="margin-top:8px;font-size:0.8rem;color:#a5d6a7"></div>
</div>
<div style="padding:12px 16px;border-top:1px solid #2a3040;background:#0d1320">
  <h3 style="font-size:0.85rem;color:#ffcc80;margin-bottom:8px">Phase 1: QR SSH Key Pairing</h3>
  <div style="font-size:0.78rem;color:#b0bec5;margin-bottom:8px">Step 1 on device A: Generate init payload and show as QR. Step 2 on device B: scan and respond. Step 3 on A: scan response and finalize. Verify SAS on both devices.</div>
  <input id="pairDeviceName" placeholder="This device name" style="width:100%;margin-bottom:8px;background:#1a1f2e;border:1px solid #2a3040;border-radius:6px;padding:8px;color:#e0e0e0" />
  <textarea id="pairPublicKey" rows="3" placeholder="This device SSH public key (ssh-ed25519 ...)
" style="width:100%;margin-bottom:8px;background:#1a1f2e;border:1px solid #2a3040;border-radius:6px;padding:8px;color:#e0e0e0"></textarea>
  <button onclick="createQrInit()" style="background:#ef6c00;color:#fff;border:none;border-radius:6px;padding:8px 12px">Create Init Payload</button>
  <div id="pairStatus" style="margin-top:8px;font-size:0.8rem;color:#ffe0b2"></div>
  <textarea id="initPayload" rows="3" placeholder="Init payload (encode this as QR for device B)" style="width:100%;margin-top:8px;background:#1a1f2e;border:1px solid #2a3040;border-radius:6px;padding:8px;color:#e0e0e0"></textarea>
  <button onclick="createQrResponse()" style="margin-top:8px;background:#00897b;color:#fff;border:none;border-radius:6px;padding:8px 12px">Create Response From Init</button>
  <textarea id="responsePayload" rows="3" placeholder="Response payload (encode this as QR back to device A)" style="width:100%;margin-top:8px;background:#1a1f2e;border:1px solid #2a3040;border-radius:6px;padding:8px;color:#e0e0e0"></textarea>
  <button onclick="finalizeQrPairing()" style="margin-top:8px;background:#5e35b1;color:#fff;border:none;border-radius:6px;padding:8px 12px">Finalize From Response</button>
  <div id="pairedKeys" style="margin-top:8px;font-size:0.8rem;color:#ffe0b2"></div>
</div>
<script>
let lastMsgCount = 0;
const myIp = location.hostname;

function getName() {
  return document.getElementById('nameInput').value.trim() || 'Anonymous@' + myIp;
}

async function loadMessages() {
  try {
    const r = await fetch('/api/messages');
    const msgs = await r.json();
    if (msgs.length !== lastMsgCount) {
      lastMsgCount = msgs.length;
      const el = document.getElementById('messages');
      const atBottom = el.scrollHeight - el.clientHeight <= el.scrollTop + 5;
      el.innerHTML = msgs.map(m => {
        const own = m.sender.includes(myIp);
        return '<div class="msg' + (own ? ' own' : '') + '">' +
          '<div class="msg-header">' + esc(m.sender) + ' · ' + m.time + '</div>' +
          '<div class="msg-body">' + esc(m.content) + '</div></div>';
      }).join('');
      if (atBottom || lastMsgCount <= 1) el.scrollTop = el.scrollHeight;
    }
  } catch(e) {}
}

async function loadClients() {
  try {
    const r = await fetch('/api/clients');
    const clients = await r.json();
    document.getElementById('status').textContent = clients.length + ' device(s)';
    document.getElementById('clients').innerHTML = clients.map(c =>
      '<div class="client-item">' + c.ip + '</div>'
    ).join('');
  } catch(e) {}
}

async function sendMsg() {
  const input = document.getElementById('msgInput');
  const content = input.value.trim();
  if (!content) return;
  input.value = '';
  try {
    await fetch('/api/send', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: 'sender=' + encodeURIComponent(getName()) + '&content=' + encodeURIComponent(content)
    });
    await loadMessages();
  } catch(e) {}
}

function esc(s) {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

async function loadTunnels() {
  try {
    const r = await fetch('/api/tunnels');
    const tunnels = await r.json();
    if (!tunnels.length) {
      document.getElementById('tunnelStatus').textContent = 'No active tunnel';
      return;
    }
    const t = tunnels[0];
    document.getElementById('tunnelStatus').textContent =
      'Listening :' + t.listenPort + ' -> ' + t.targetHost + ':' + t.targetPort +
      ' | active connections: ' + t.activeConnections + (t.lastError ? (' | last error: ' + t.lastError) : '');
  } catch (e) {}
}

async function startTunnel() {
  const targetHost = document.getElementById('targetHost').value.trim();
  const targetPort = document.getElementById('targetPort').value.trim() || '22';
  const listenPort = document.getElementById('listenPort').value.trim() || '2222';
  if (!targetHost) return;
  try {
    await fetch('/api/tunnel/start', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: 'targetHost=' + encodeURIComponent(targetHost) + '&targetPort=' + encodeURIComponent(targetPort) + '&listenPort=' + encodeURIComponent(listenPort)
    });
    loadTunnels();
  } catch (e) {}
}

async function stopTunnel() {
  const listenPort = document.getElementById('listenPort').value.trim() || '2222';
  try {
    await fetch('/api/tunnel/stop', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: 'listenPort=' + encodeURIComponent(listenPort)
    });
    loadTunnels();
  } catch (e) {}
}

async function loadPairedKeys() {
  try {
    const r = await fetch('/api/keys');
    const keys = await r.json();
    const el = document.getElementById('pairedKeys');
    if (!keys.length) {
      el.textContent = 'No paired keys yet.';
      return;
    }
    el.innerHTML = keys.map(k =>
      '<div style="margin-top:6px;padding:6px;border:1px solid #2a3040;border-radius:6px">' +
      '<b>' + esc(k.deviceName) + '</b><br/>' +
      '<code>' + esc(k.fingerprint) + '</code> ' +
      '<button onclick="unpairKey(\'' + k.id + '\')" style="margin-left:8px;background:#6d4c41;color:#fff;border:none;border-radius:4px;padding:2px 6px">Remove</button>' +
      '</div>'
    ).join('');
  } catch (e) {}
}

async function createQrInit() {
  const deviceName = document.getElementById('pairDeviceName').value.trim() || 'Device A';
  const publicKey = document.getElementById('pairPublicKey').value.trim();
  const status = document.getElementById('pairStatus');
  if (!publicKey) {
    status.textContent = 'Public key is required.';
    return;
  }
  try {
    const r = await fetch('/api/pairing/qr/init', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: 'deviceName=' + encodeURIComponent(deviceName) + '&publicKey=' + encodeURIComponent(publicKey)
    });
    const data = await r.json();
    if (data.status === 'ok') {
      document.getElementById('initPayload').value = data.payload;
      status.textContent = 'Init created. Verify SAS on both devices: ' + data.sas;
    } else {
      status.textContent = data.message || 'Init failed';
    }
  } catch (e) {}
}

async function createQrResponse() {
  const initPayload = document.getElementById('initPayload').value.trim();
  const deviceName = document.getElementById('pairDeviceName').value.trim() || 'Device B';
  const publicKey = document.getElementById('pairPublicKey').value.trim();
  const status = document.getElementById('pairStatus');
  if (!initPayload || !publicKey) {
    status.textContent = 'Init payload and public key are required.';
    return;
  }
  try {
    const r = await fetch('/api/pairing/qr/respond', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: 'initPayload=' + encodeURIComponent(initPayload) + '&deviceName=' + encodeURIComponent(deviceName) + '&publicKey=' + encodeURIComponent(publicKey)
    });
    const data = await r.json();
    if (data.status === 'ok') {
      document.getElementById('responsePayload').value = data.payload;
      status.textContent = 'Response created. Verify SAS: ' + data.sas;
    } else {
      status.textContent = data.message || 'Response failed';
    }
  } catch (e) {}
}

async function finalizeQrPairing() {
  const responsePayload = document.getElementById('responsePayload').value.trim();
  const status = document.getElementById('pairStatus');
  if (!responsePayload) {
    status.textContent = 'Response payload is required.';
    return;
  }
  try {
    const r = await fetch('/api/pairing/qr/finalize', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: 'responsePayload=' + encodeURIComponent(responsePayload)
    });
    const data = await r.json();
    if (data.status === 'ok') {
      status.textContent = 'Pairing complete. Fingerprint: ' + data.fingerprint + '. SAS verified: ' + data.sas;
      document.getElementById('responsePayload').value = '';
      loadPairedKeys();
    } else {
      status.textContent = data.message || 'Finalize failed';
    }
  } catch (e) {}
}

async function unpairKey(id) {
  try {
    await fetch('/api/keys/unpair', {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: 'id=' + encodeURIComponent(id)
    });
    loadPairedKeys();
  } catch (e) {}
}

setInterval(loadMessages, 1500);
setInterval(loadClients, 3000);
setInterval(loadTunnels, 3000);
setInterval(loadPairedKeys, 5000);
loadMessages();
loadClients();
loadTunnels();
loadPairedKeys();
</script>
</body>
</html>
    """.trimIndent()
}
