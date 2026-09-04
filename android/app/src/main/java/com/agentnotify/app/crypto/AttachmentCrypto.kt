package com.agentnotify.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

object AttachmentCrypto {
    private const val KEY_ALIAS = "agent-notify-attachment-key-v1"

    fun publicKeyBase64(): String {
        ensureKeyPair()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return Base64.encodeToString(store.getCertificate(KEY_ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }

    fun decryptingStream(encryptedInput: java.io.InputStream, wrappedKey: String, iv: String): CipherInputStream {
        ensureKeyPair()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = store.getKey(KEY_ALIAS, null)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(
            Cipher.DECRYPT_MODE,
            privateKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT),
        )
        val aesKey = rsa.doFinal(Base64.decode(wrappedKey, Base64.DEFAULT))
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
        return CipherInputStream(encryptedInput, aes)
    }

    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun ensureKeyPair() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .build()
        )
        generator.generateKeyPair()
    }
}
