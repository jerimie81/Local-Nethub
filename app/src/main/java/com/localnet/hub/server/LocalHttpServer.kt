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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val messages = CopyOnWriteArrayList<ChatMessage>()
    val connectedClients = CopyOnWriteArrayList<ConnectedClient>()
    var onUpdate: (() -> Unit)? = null

    var isRunning = false
        private set

    fun start() {
        if (isRunning) return
        isRunning = true
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Server started on port $port")
                while (isActive && !serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        serverSocket?.close()
        scope.cancel()
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
            val queryString = if (rawPath.contains("?")) rawPath.substringAfter("?") else ""

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
                    reader.read(chars, 0, contentLength)
                    body = String(chars)
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
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "OK" }
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
            val kv = pair.split("=")
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

setInterval(loadMessages, 1500);
setInterval(loadClients, 3000);
loadMessages();
loadClients();
</script>
</body>
</html>
    """.trimIndent()
}
