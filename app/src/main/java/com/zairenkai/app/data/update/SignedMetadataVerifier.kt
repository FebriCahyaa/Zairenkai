// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
/*
 * Ed25519 verification for the stable-channel update manifest. The public key
 * is pinned as an app asset; the signature, exact payload hash, file name and
 * key identifier must all match before the manifest is parsed.
 */
package com.zairenkai.app.data.update

import android.content.Context
import java.security.KeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal object SignedMetadataVerifier {
    private const val MAGIC = "ZKFC-METADATA-SIGNATURE-V1"
    private val filenamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun verifyAsset(context: Context, fileName: String, payload: ByteArray, envelope: ByteArray): Boolean = try {
        val publicPem = context.assets.open(PUBLIC_KEY_ASSET).use { it.readBytes() }
        verify(payload, fileName, envelope, publicPem)
    } catch (_: Exception) {
        false
    }

    /** Exposed internally so unit tests can use ephemeral, non-production keys. */
    internal fun verify(payload: ByteArray, fileName: String, envelopeBytes: ByteArray, publicKeyPem: ByteArray): Boolean = try {
        if (!filenamePattern.matches(fileName) || fileName.contains("..")) return false
        val pem = publicKeyPem.toString(Charsets.US_ASCII)
        val begin = "-----BEGIN PUBLIC KEY-----"
        val end = "-----END PUBLIC KEY-----"
        if (!pem.contains(begin) || !pem.contains(end) || pem.contains("UNPROVISIONED")) return false
        val encoded = pem.substringAfter(begin).substringBefore(end).filterNot(Char::isWhitespace)
        val der = Base64.getDecoder().decode(encoded)
        // Android 12 (the app's minSdk) does not guarantee a platform Ed25519
        // provider. Pin Bouncy Castle explicitly instead of relying on OS version.
        val provider = BouncyCastleProvider()
        val publicKey = KeyFactory.getInstance("Ed25519", provider).generatePublic(X509EncodedKeySpec(der))
        val lines = envelopeBytes.toString(Charsets.US_ASCII).trimEnd('\r', '\n').split('\n')
        if (lines.size != 5 || lines.firstOrNull() != MAGIC) return false
        val fields = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val split = line.split('=', limit = 2)
            if (split.size != 2 || split[0].isBlank() || fields.put(split[0], split[1]) != null) return false
        }
        if (fields.keys != setOf("file", "key_id", "sha256", "signature")) return false
        if (fields["file"] != fileName) return false
        val keyId = sha256(publicKey.encoded)
        if (!constantTimeEquals(fields["key_id"].orEmpty(), keyId)) return false
        if (!constantTimeEquals(fields["sha256"].orEmpty(), sha256(payload))) return false
        val signatureBytes = Base64.getDecoder().decode(fields["signature"])
        val verifier = Signature.getInstance("Ed25519", provider)
        verifier.initVerify(publicKey)
        verifier.update(payload)
        verifier.verify(signatureBytes)
    } catch (_: Exception) {
        false
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        return MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))
    }

    private const val PUBLIC_KEY_ASSET = "security/update-metadata-public-key.pem"
}
