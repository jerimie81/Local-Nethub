package com.localnet.hub.server

import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

data class PairedSshKey(
    val id: String,
    val deviceName: String,
    val publicKey: String,
    val fingerprint: String,
    val pairedAt: Long = System.currentTimeMillis()
)

class SshKeyPairingManager {

    private val pairedKeys = CopyOnWriteArrayList<PairedSshKey>()

    fun listKeys(): List<PairedSshKey> = pairedKeys.toList()

    fun pairKey(deviceName: String, publicKey: String): Result<PairedSshKey> {
        val normalizedName = deviceName.trim().ifBlank { "Unnamed device" }
        val normalizedKey = publicKey.trim()

        if (!isLikelySshPublicKey(normalizedKey)) {
            return Result.failure(IllegalArgumentException("Invalid SSH public key format"))
        }

        val fp = fingerprint(normalizedKey)
        val existing = pairedKeys.firstOrNull { it.fingerprint == fp }
        if (existing != null) {
            return Result.success(existing)
        }

        val key = PairedSshKey(
            id = fp,
            deviceName = normalizedName,
            publicKey = normalizedKey,
            fingerprint = fp
        )
        pairedKeys.add(key)
        return Result.success(key)
    }

    fun unpairKey(id: String): Boolean = pairedKeys.removeIf { it.id == id }

    private fun isLikelySshPublicKey(value: String): Boolean {
        val prefixes = listOf("ssh-ed25519 ", "ssh-rsa ", "ecdsa-sha2-")
        return prefixes.any { value.startsWith(it) }
    }

    private fun fingerprint(publicKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.toByteArray())
        val b64 = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
        return "SHA256:$b64"
    }
}
