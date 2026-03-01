package com.localnet.hub.server

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages local TCP tunnels that forward traffic between two endpoints.
 * Intended for SSH port-forwarding across devices on the same local network.
 */
class SshTunnelManager {

    data class TunnelConfig(
        val listenPort: Int,
        val targetHost: String,
        val targetPort: Int = 22,
    )

    data class TunnelRuntime(
        val config: TunnelConfig,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        // AtomicInteger avoids the lost-update race that @Volatile Int += 1 has.
        internal val connectionCounter = AtomicInteger(0)

        @Volatile
        var lastError: String = ""

        val activeConnections: Int get() = connectionCounter.get()
    }

    private data class TunnelHandle(
        val runtime: TunnelRuntime,
        val serverSocket: ServerSocket,
        val scope: CoroutineScope,
        val job: Job,
    )

    private companion object {
        const val TAG = "SshTunnelManager"
        const val IO_BUFFER_SIZE = 8 * 1024
    }

    private val tunnels = ConcurrentHashMap<Int, TunnelHandle>()

    fun startTunnel(
        listenPort: Int,
        targetHost: String,
        targetPort: Int = 22,
    ): Result<TunnelRuntime> {
        if (listenPort !in 1..65535) {
            return Result.failure(IllegalArgumentException("Invalid listen port"))
        }
        if (targetPort !in 1..65535) {
            return Result.failure(IllegalArgumentException("Invalid target port"))
        }
        if (targetHost.isBlank()) {
            return Result.failure(IllegalArgumentException("Target host required"))
        }
        if (tunnels.containsKey(listenPort)) {
            return Result.failure(IllegalStateException("Tunnel already running on port $listenPort"))
        }

        // reuseAddress MUST be set before bind() — setting it after has no effect.
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(listenPort))

        val runtime = TunnelRuntime(
            config = TunnelConfig(listenPort, targetHost, targetPort),
        )
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = scope.launch {
            Log.i(TAG, "Tunnel :$listenPort -> $targetHost:$targetPort started")
            while (isActive && !serverSocket.isClosed) {
                try {
                    val incoming = serverSocket.accept()
                    launch { proxyConnection(runtime, incoming) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    runtime.lastError = e.message ?: "Accept error"
                    Log.e(TAG, "Accept error on :$listenPort", e)
                }
            }
        }

        tunnels[listenPort] = TunnelHandle(runtime, serverSocket, scope, job)
        return Result.success(runtime)
    }

    fun stopTunnel(listenPort: Int): Boolean {
        val handle = tunnels.remove(listenPort) ?: return false
        runCatching { handle.job.cancel() }
        runCatching { handle.serverSocket.close() }
        runCatching { handle.scope.cancel() }
        Log.i(TAG, "Tunnel :$listenPort stopped")
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
            runtime.connectionCounter.incrementAndGet()

            // Two plain threads relay bytes in each direction.
            // join() on a thread is safe here — this whole function runs on an IO thread
            // dispatched via scope.launch{ }, so blocking it doesn't starve the main thread.
            val c2s = Thread { copy(incoming.getInputStream(), upstream.getOutputStream()) }
            val s2c = Thread { copy(upstream.getInputStream(), incoming.getOutputStream()) }
            c2s.start()
            s2c.start()
            c2s.join()
            s2c.join()
        } catch (e: Exception) {
            runtime.lastError = e.message ?: "Proxy error"
            Log.e(TAG, "Proxy connection error", e)
        } finally {
            runtime.connectionCounter.decrementAndGet()
            runCatching { incoming.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buf = ByteArray(IO_BUFFER_SIZE)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }
}
