package com.localnet.hub.server

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Simple local TCP tunnel manager intended for SSH traffic forwarding between
 * devices connected to the same host/server network.
 */
class SshTunnelManager {

    data class TunnelConfig(
        val listenPort: Int,
        val targetHost: String,
        val targetPort: Int = 22
    )

    data class TunnelRuntime(
        val config: TunnelConfig,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var activeConnections: Int = 0,
        @Volatile var lastError: String = ""
    )

    private data class TunnelHandle(
        val runtime: TunnelRuntime,
        val serverSocket: ServerSocket,
        val scope: CoroutineScope,
        val job: Job
    )

    private val tag = "SshTunnelManager"
    private val tunnels = ConcurrentHashMap<Int, TunnelHandle>()

    fun startTunnel(listenPort: Int, targetHost: String, targetPort: Int = 22): Result<TunnelRuntime> {
        if (listenPort !in 1..65535) return Result.failure(IllegalArgumentException("Invalid listen port"))
        if (targetPort !in 1..65535) return Result.failure(IllegalArgumentException("Invalid target port"))
        if (targetHost.isBlank()) return Result.failure(IllegalArgumentException("Target host required"))
        if (tunnels.containsKey(listenPort)) {
            return Result.failure(IllegalStateException("Tunnel already running on port $listenPort"))
        }

        val serverSocket = ServerSocket(listenPort)
        serverSocket.reuseAddress = true

        val runtime = TunnelRuntime(
            config = TunnelConfig(listenPort = listenPort, targetHost = targetHost, targetPort = targetPort)
        )
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = scope.launch {
            Log.i(tag, "SSH tunnel listening on :$listenPort -> $targetHost:$targetPort")
            while (isActive && !serverSocket.isClosed) {
                try {
                    val incoming = serverSocket.accept()
                    scope.launch { proxyConnection(runtime, incoming) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    runtime.lastError = e.message ?: "Tunnel accept error"
                    Log.e(tag, "Tunnel accept error on :$listenPort", e)
                }
            }
        }

        tunnels[listenPort] = TunnelHandle(runtime, serverSocket, scope, job)
        return Result.success(runtime)
    }

    fun stopTunnel(listenPort: Int): Boolean {
        val handle = tunnels.remove(listenPort) ?: return false
        try {
            handle.job.cancel()
            handle.serverSocket.close()
            handle.scope.cancel()
        } catch (_: Exception) {
        }
        return true
    }

    fun stopAll() {
        tunnels.keys.toList().forEach { stopTunnel(it) }
    }

    fun listTunnels(): List<TunnelRuntime> = tunnels.values.map { it.runtime }

    private fun proxyConnection(runtime: TunnelRuntime, incoming: Socket) {
        var upstream: Socket? = null
        try {
            upstream = Socket(runtime.config.targetHost, runtime.config.targetPort)
            runtime.activeConnections += 1

            val c2s = Thread {
                copy(incoming.getInputStream(), upstream.getOutputStream())
            }
            val s2c = Thread {
                copy(upstream.getInputStream(), incoming.getOutputStream())
            }

            c2s.start()
            s2c.start()
            c2s.join()
            s2c.join()
        } catch (e: Exception) {
            runtime.lastError = e.message ?: "Proxy connection error"
            Log.e(tag, "Proxy connection error", e)
        } finally {
            runtime.activeConnections = (runtime.activeConnections - 1).coerceAtLeast(0)
            try {
                incoming.close()
            } catch (_: Exception) {
            }
            try {
                upstream?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }
}
