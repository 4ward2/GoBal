package com.danielealbano.androidremotecontrolmcp.services.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Authenticated encryption for vault files. */
interface VaultCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws javax.crypto.AEADBadTagException when the blob was tampered with. */
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore (StrongBox when the device
 * has one, otherwise the TEE). The key never exists in this process's memory, so copying the
 * vault files off the phone yields nothing usable. Blob layout: 12-byte IV ‖ ciphertext+tag.
 */
@Singleton
class KeystoreVaultCipher
    @Inject
    constructor() : VaultCipher {
        override fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            check(iv.size == IV_BYTES) { "unexpected IV size ${iv.size}" }
            return iv + cipher.doFinal(plain)
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            require(blob.size > IV_BYTES) { "vault blob too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }

        @Synchronized
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return try {
                generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                Log.i(TAG, "StrongBox unavailable, using TEE-backed key: ${e.message}")
                generate(strongBox = false)
            }
        }

        private fun generate(strongBox: Boolean): SecretKey {
            val spec =
                KeyGenParameterSpec
                    .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .setIsStrongBoxBacked(strongBox)
                    .build()
            return KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        }

        companion object {
            private const val TAG = "MCP:VaultCipher"
            private const val KEYSTORE = "AndroidKeyStore"
            private const val ALIAS = "phonemcp_vault_v1"
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val KEY_BITS = 256
            private const val TAG_BITS = 128
            private const val IV_BYTES = 12
        }
    }
