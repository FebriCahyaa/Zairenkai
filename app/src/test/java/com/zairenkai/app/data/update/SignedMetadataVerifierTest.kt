// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
package com.zairenkai.app.data.update

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedMetadataVerifierTest {
    private val payload = """{"apiMajor":1,"versionCode":7,"versionName":"1.2.3"}""".toByteArray()

    private fun envelope(fileName: String = "version-release.json", content: ByteArray = payload): Pair<ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val keyId = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val hash = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(pair.private)
        signer.update(content)
        val sig = Base64.getEncoder().encodeToString(signer.sign())
        val envelope = """ZKFC-METADATA-SIGNATURE-V1
file=$fileName
key_id=$keyId
sha256=$hash
signature=$sig
""".toByteArray(Charsets.US_ASCII)
        val pem = "-----BEGIN PUBLIC KEY-----\n${Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded)}\n-----END PUBLIC KEY-----\n".toByteArray(Charsets.US_ASCII)
        return envelope to pem
    }

    @Test fun acceptsAuthenticPayload() {
        val (envelope, key) = envelope()
        assertTrue(SignedMetadataVerifier.verify(payload, "version-release.json", envelope, key))
    }

    @Test fun rejectsModifiedPayload() {
        val (envelope, key) = envelope()
        assertFalse(SignedMetadataVerifier.verify(payload + byteArrayOf(0x20), "version-release.json", envelope, key))
    }

    @Test fun rejectsWrongFilename() {
        val (envelope, key) = envelope()
        assertFalse(SignedMetadataVerifier.verify(payload, "version-canary.json", envelope, key))
    }

    @Test fun rejectsUnprovisionedOrMalformedKey() {
        val (envelope, _) = envelope()
        assertFalse(SignedMetadataVerifier.verify(payload, "version-release.json", envelope, "UNPROVISIONED".toByteArray()))
        assertFalse(SignedMetadataVerifier.verify(payload, "../version-release.json", envelope, "UNPROVISIONED".toByteArray()))
    }
}
