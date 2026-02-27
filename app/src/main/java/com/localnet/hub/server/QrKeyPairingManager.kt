package com.localnet.hub.server

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class QrPairingInit(
    val payload: String,
    val sessionId: String,
    val sas: String,
    val expiresAt: Long
)

data class QrPairingResponse(
    val payload: String,
    val sessionId: String,
    val sas: String,
    val responderFingerprint: String,
    val expiresAt: Long
)

data class QrPairingFinalize(
    val pairedKey: PairedSshKey,
    val sessionId: String,
    val sas: String
)

class QrKeyPairingManager(
    private val keyPairingManager: SshKeyPairingManager,
    private val sessionTtlMs: Long = 120_000
) {

    private data class PendingSession(
        val sessionId: String,
        val nonce: String,
        val initiatorFingerprint: String,
        val sas: String,
        val expiresAt: Long
    )

    private val sessions = ConcurrentHashMap<String, PendingSession>()

    fun createInitPayload(deviceName: String, publicKey: String): Result<QrPairingInit> {
        val pairResult = keyPairingManager.validate(deviceName = deviceName, publicKey = publicKey)
            .getOrElse { return Result.failure(it) }

        pruneExpired()
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val expiresAt = System.currentTimeMillis() + sessionTtlMs
        val sas = shortCode("$sessionId|$nonce|${pairResult.fingerprint}|$expiresAt")

        sessions[sessionId] = PendingSession(
            sessionId = sessionId,
            nonce = nonce,
            initiatorFingerprint = pairResult.fingerprint,
            sas = sas,
            expiresAt = expiresAt
        )

        val payload = encodePayload(
            type = "init",
            values = mapOf(
                "sid" to sessionId,
                "nonce" to nonce,
                "exp" to expiresAt.toString(),
                "sas" to sas,
                "name" to pairResult.deviceName,
                "key" to pairResult.publicKey
            )
        )

        return Result.success(QrPairingInit(payload, sessionId, sas, expiresAt))
    }

    fun createResponsePayload(initPayload: String, deviceName: String, publicKey: String): Result<QrPairingResponse> {
        val initData = decodePayload(initPayload).getOrElse { return Result.failure(it) }
        if (initData["type"] != "init") return Result.failure(IllegalArgumentException("Invalid init payload type"))

        val pairResult = keyPairingManager.validate(deviceName = deviceName, publicKey = publicKey)
            .getOrElse { return Result.failure(it) }

        val sessionId = initData["sid"].orEmpty()
        val nonce = initData["nonce"].orEmpty()
        val expiresAt = initData["exp"]?.toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("Missing expiration"))
        val sas = initData["sas"].orEmpty()
        val initiatorFingerprint = initData["key"]?.let { keyPairingManager.fingerprintFor(it) }
            ?: return Result.failure(IllegalArgumentException("Missing initiator key"))

        if (sessionId.isBlank() || nonce.isBlank() || sas.isBlank()) {
            return Result.failure(IllegalArgumentException("Malformed init payload"))
        }
        if (System.currentTimeMillis() > expiresAt) {
            return Result.failure(IllegalStateException("Pairing request expired"))
        }

        val expectedSas = shortCode("$sessionId|$nonce|$initiatorFingerprint|$expiresAt")
        if (sas != expectedSas) {
            return Result.failure(IllegalArgumentException("SAS mismatch"))
        }

        val proof = hash("$sessionId|$nonce|${pairResult.fingerprint}|$sas")
        val payload = encodePayload(
            type = "resp",
            values = mapOf(
                "sid" to sessionId,
                "nonce" to nonce,
                "exp" to expiresAt.toString(),
                "sas" to sas,
                "name" to pairResult.deviceName,
                "key" to pairResult.publicKey,
                "proof" to proof
            )
        )

        return Result.success(
            QrPairingResponse(
                payload = payload,
                sessionId = sessionId,
                sas = sas,
                responderFingerprint = pairResult.fingerprint,
                expiresAt = expiresAt
            )
        )
    }

    fun finalizeFromResponse(responsePayload: String): Result<QrPairingFinalize> {
        pruneExpired()
        val response = decodePayload(responsePayload).getOrElse { return Result.failure(it) }
        if (response["type"] != "resp") return Result.failure(IllegalArgumentException("Invalid response payload type"))

        val sessionId = response["sid"].orEmpty()
        val nonce = response["nonce"].orEmpty()
        val expiresAt = response["exp"]?.toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("Missing expiration"))
        val sas = response["sas"].orEmpty()
        val responderName = response["name"].orEmpty()
        val responderKey = response["key"].orEmpty()
        val proof = response["proof"].orEmpty()

        val pending = sessions[sessionId]
            ?: return Result.failure(IllegalStateException("Unknown or expired pairing session"))

        if (pending.expiresAt != expiresAt || System.currentTimeMillis() > expiresAt) {
            sessions.remove(sessionId)
            return Result.failure(IllegalStateException("Pairing session expired"))
        }
        if (pending.nonce != nonce || pending.sas != sas) {
            return Result.failure(IllegalArgumentException("Pairing session mismatch"))
        }

        val responderFingerprint = keyPairingManager.fingerprintFor(responderKey)
        val expectedProof = hash("$sessionId|$nonce|$responderFingerprint|$sas")
        if (proof != expectedProof) {
            return Result.failure(IllegalArgumentException("Response proof mismatch"))
        }

        val paired = keyPairingManager.pairKey(responderName, responderKey).getOrElse { return Result.failure(it) }
        sessions.remove(sessionId)
        return Result.success(QrPairingFinalize(paired, sessionId, sas))
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAt < now }
    }

    private fun encodePayload(type: String, values: Map<String, String>): String {
        val body = values.entries.joinToString("|") { (k, v) -> "$k=${enc(v)}" }
        return "lnh1|type=$type|$body"
    }

    private fun decodePayload(payload: String): Result<Map<String, String>> {
        if (!payload.startsWith("lnh1|")) return Result.failure(IllegalArgumentException("Invalid payload prefix"))
        val parts = payload.split("|")
        val map = mutableMapOf<String, String>()
        for (part in parts.drop(1)) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val k = part.substring(0, idx)
            val v = part.substring(idx + 1)
            map[k] = dec(v)
        }
        return Result.success(map)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun shortCode(seed: String): String = hash(seed).take(6).uppercase()

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun dec(value: String) = URLDecoder.decode(value, "UTF-8")
}
