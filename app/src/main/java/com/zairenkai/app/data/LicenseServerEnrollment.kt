// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Explicit opt-in enrollment with the Zairenkai License Server.
 * The enrollment key is installation-local and AES-GCM encrypted with a key
 * protected by Android Keystore. This registers an installation only; it does
 * not issue or activate a kernel ZKFC API token.
 */
package com.zairenkai.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.zairenkai.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Minimal, privacy-preserving server enrollment status cached on this device. */
data class LicenseServerEnrollmentStatus(
    val configured: Boolean,
    val registered: Boolean,
    val installationId: String? = null,
    val fingerprint: String? = null,
    val lastSuccessAt: Long? = null,
    val errorCode: String? = null,
)

data class LicenseServerEnrollmentResult(
    val status: LicenseServerEnrollmentStatus,
    val successful: Boolean,
    val alreadyRegistered: Boolean = false,
    val errorCode: String? = null,
)

class LicenseServerEnrollment(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyFile = java.io.File(appContext.filesDir, KEY_FILE)
    private val provider = BouncyCastleProvider()
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** Shows cached state only. No network request happens when the screen opens. */
    fun cachedStatus(): LicenseServerEnrollmentStatus {
        val origin = configuredOrigin()
        val sameServer = origin != null && preferences.getString(KEY_ORIGIN, null) == origin
        val id = preferences.getString(KEY_INSTALLATION_ID, null).takeIf { sameServer }
        val fingerprint = preferences.getString(KEY_FINGERPRINT, null).takeIf { sameServer }
        return LicenseServerEnrollmentStatus(
            configured = origin != null,
            registered = !id.isNullOrBlank() && !fingerprint.isNullOrBlank(),
            installationId = id,
            fingerprint = fingerprint,
            lastSuccessAt = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { sameServer && it > 0L },
            errorCode = preferences.getString(KEY_LAST_ERROR, null).takeIf { sameServer },
        )
    }

    /** User-triggered two-step challenge/proof enrollment. */
    suspend fun enroll(): LicenseServerEnrollmentResult = withContext(Dispatchers.IO) {
        val origin = configuredOrigin()
        if (origin == null) {
            val status = cachedStatus().copy(configured = false, errorCode = "server_not_configured")
            return@withContext LicenseServerEnrollmentResult(status, false, errorCode = "server_not_configured")
        }

        try {
            val pair = loadOrCreateKeyPair()
            val challenge = postJson(origin, "/v1/enrollment/challenge", buildJsonObject {})
            val challengeId = challenge.string("challengeId") ?: fail("invalid_challenge_response")
            val nonce = challenge.string("nonce") ?: fail("invalid_challenge_response")
            val expiresAt = challenge.long("expiresAt") ?: fail("invalid_challenge_response")
            if (challenge.string("protocol") != PROTOCOL || challenge.string("algorithm") != "Ed25519") {
                fail("unsupported_enrollment_protocol")
            }
            // The server enforces challenge expiration. Only validate that a positive
            // epoch was supplied here; Android wall clocks can be skewed substantially.
            if (expiresAt <= 0L) fail("invalid_challenge_expiry")

            val publicRaw = rawEd25519Public(pair)
            val publicKeyText = base64Url(publicRaw)
            val fingerprint = sha256Hex(publicRaw)
            val signingText = "$PROTOCOL\n$challengeId\n$nonce\n$fingerprint"
            val signer = Signature.getInstance("Ed25519", provider).apply {
                initSign(pair.private)
                update(signingText.toByteArray(StandardCharsets.UTF_8))
            }
            val signatureText = base64Url(signer.sign())
            val completed = postJson(
                origin,
                "/v1/enrollment/complete",
                buildJsonObject {
                    put("challengeId", challengeId)
                    put("nonce", nonce)
                    put("publicKey", publicKeyText)
                    put("signature", signatureText)
                },
            )

            val installationId = completed.string("installationId")
                ?: fail("invalid_enrollment_response")
            val serverFingerprint = completed.string("fingerprint")
                ?: fail("invalid_enrollment_response")
            val serverStatus = completed.string("status")
            if (serverStatus != "active" || serverFingerprint != fingerprint) {
                fail("enrollment_identity_mismatch")
            }

            preferences.edit()
                .putString(KEY_ORIGIN, origin)
                .putString(KEY_INSTALLATION_ID, installationId)
                .putString(KEY_FINGERPRINT, fingerprint)
                .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply()

            LicenseServerEnrollmentResult(
                status = cachedStatus(),
                successful = true,
                alreadyRegistered = completed.boolean("alreadyRegistered") == true,
            )
        } catch (error: Exception) {
            val code = (error as? EnrollmentException)?.code ?: "network_or_crypto_error"
            preferences.edit().putString(KEY_LAST_ERROR, code).apply()
            LicenseServerEnrollmentResult(
                status = cachedStatus().copy(errorCode = code),
                successful = false,
                errorCode = code,
            )
        }
    }

    private fun configuredOrigin(): String? {
        val configured = BuildConfig.ZKFC_LICENSE_SERVER_URL.trim().trimEnd('/')
        if (configured.isBlank()) return null
        return runCatching {
            val uri = URI(configured)
            val host = uri.host ?: throw IllegalArgumentException("server host is missing")
            require(uri.scheme.equals("https", ignoreCase = true))
            require(uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            require(uri.port == -1 || uri.port == 443)
            require(host.matches(Regex("[A-Za-z0-9.-]+")))
            "https://${uri.rawAuthority}"
        }.getOrNull()
    }

    private fun postJson(origin: String, path: String, body: JsonObject): JsonObject {
        val url = URL(origin + path)
        require(url.protocol == "https") { "https_required" }
        val connection = (url.openConnection() as javax.net.ssl.HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "Zairenkai-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw EnrollmentException("response_too_large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            val root = runCatching {
                json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
            }.getOrNull()
            if (code !in 200..299) {
                val errorCode = root?.string("error")?.takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) }
                    ?: "http_$code"
                throw EnrollmentException(errorCode)
            }
            return root ?: throw EnrollmentException("invalid_json_response")
        } finally {
            connection.disconnect()
        }
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        if (!keyFile.exists()) {
            val generator = KeyPairGenerator.getInstance("Ed25519", provider)
            val pair = generator.generateKeyPair()
            val privateEncoded = pair.private.encoded ?: fail("private_key_encoding_failed")
            val publicRaw = rawEd25519Public(pair)
            val plaintext = ByteBuffer.allocate(4 + privateEncoded.size + publicRaw.size)
                .putInt(privateEncoded.size)
                .put(privateEncoded)
                .put(publicRaw)
                .array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Let Android Keystore generate the GCM IV because randomized
            // encryption is required by the key's policy.
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
            cipher.updateAAD(KEY_AAD.toByteArray(StandardCharsets.UTF_8))
            val encrypted = cipher.doFinal(plaintext)
            val iv = cipher.iv ?: fail("stored_enrollment_key_write_failed")
            if (iv.size != GCM_IV_BYTES) fail("stored_enrollment_key_write_failed")
            val pendingFile = java.io.File(appContext.filesDir, "$KEY_FILE.tmp")
            pendingFile.writeBytes(iv + encrypted)
            if (!pendingFile.renameTo(keyFile)) {
                pendingFile.delete()
                fail("stored_enrollment_key_write_failed")
            }
            privateEncoded.fill(0)
            plaintext.fill(0)
            return pair
        }

        val blob = keyFile.readBytes()
        if (blob.size <= GCM_IV_BYTES + GCM_TAG_BYTES) fail("stored_enrollment_key_invalid")
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(KEY_AAD.toByteArray(StandardCharsets.UTF_8))
            val plaintext = cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size))
            DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                val privateLength = input.readInt()
                if (privateLength !in 1..4096 || plaintext.size != 4 + privateLength + ED25519_PUBLIC_BYTES) {
                    fail("stored_enrollment_key_invalid")
                }
                val privateEncoded = ByteArray(privateLength).also { input.readFully(it) }
                val publicRaw = ByteArray(ED25519_PUBLIC_BYTES).also { input.readFully(it) }
                val privateKey = KeyFactory.getInstance("Ed25519", provider)
                    .generatePrivate(PKCS8EncodedKeySpec(privateEncoded))
                return KeyPair(ed25519PublicFromRaw(publicRaw), privateKey)
            }
        } catch (error: EnrollmentException) {
            throw error
        } catch (_: Exception) {
            throw EnrollmentException("stored_enrollment_key_unavailable")
        }
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun rawEd25519Public(pair: KeyPair): ByteArray {
        val encoded = pair.public.encoded ?: fail("public_key_encoding_failed")
        if (encoded.size < ED25519_PUBLIC_BYTES) fail("public_key_encoding_failed")
        return encoded.copyOfRange(encoded.size - ED25519_PUBLIC_BYTES, encoded.size)
    }

    private fun ed25519PublicFromRaw(raw: ByteArray): java.security.PublicKey {
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        return KeyFactory.getInstance("Ed25519", provider)
            .generatePublic(java.security.spec.X509EncodedKeySpec(prefix + raw))
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    private fun fail(code: String): Nothing = throw EnrollmentException(code)

    private class EnrollmentException(val code: String) : Exception(code)

    private companion object {
        const val PREFS = "zairenkai_license_server"
        const val KEY_ALIAS = "zairenkai_license_server_enrollment_aes_gcm_v1"
        const val KEY_FILE = "license-server-enrollment-key.bin"
        const val KEY_AAD = "Zairenkai-License-Server-Enrollment-Key-v1"
        const val PROTOCOL = "ZKFC-ENROLL-v1"
        const val MAX_RESPONSE_BYTES = 32 * 1024
        const val ED25519_PUBLIC_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val KEY_ORIGIN = "origin"
        const val KEY_INSTALLATION_ID = "installation_id"
        const val KEY_FINGERPRINT = "fingerprint"
        const val KEY_LAST_SUCCESS = "last_success_ms"
        const val KEY_LAST_ERROR = "last_error"
    }
}
