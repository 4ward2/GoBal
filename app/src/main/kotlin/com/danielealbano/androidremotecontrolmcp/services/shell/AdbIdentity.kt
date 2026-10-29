package com.danielealbano.androidremotecontrolmcp.services.shell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.services.chat.AtomicFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The key this app shows to the phone's wireless debugging: made on first use, kept in
 * `files/shell/adbkey.bin` sealed by a Keystore key, never backed up and never logged.
 * Pairing teaches the phone this key; every later connection proves it over TLS.
 */
@Singleton
class AdbIdentity
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val file get() = File(File(context.filesDir, DIR), "adbkey.bin")

        private val privateKey: RSAPrivateCrtKey by lazy { load() ?: create() }

        private val certificate: X509Certificate by lazy { selfSigned(privateKey) }

        /** What pairing hands to the phone. */
        val adbPublicKey: ByteArray by lazy {
            AdbPublicKey.encode(privateKey.modulus, privateKey.publicExponent, NAME)
        }

        /** TLS 1.3 presenting [certificate]; the bundled Conscrypt, because pairing needs its key export. */
        val sslContext: SSLContext by lazy {
            SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
                init(arrayOf(Keys(privateKey, certificate)), arrayOf(AnyPeer), SecureRandom())
            }
        }

        private fun load(): RSAPrivateCrtKey? {
            val sealed = file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } ?: return null
            return runCatching {
                val bytes = Base64.getDecoder().decode(sealed.trim())
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, sealingKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV))
                val plain = cipher.doFinal(bytes, IV, bytes.size - IV)
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(plain)) as RSAPrivateCrtKey
            }.onFailure { Log.w(TAG, "stored key unreadable: ${it.javaClass.simpleName}") }.getOrNull()
        }

        private fun create(): RSAPrivateCrtKey {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(RSAKeyGenParameterSpec(RSA_BITS, RSAKeyGenParameterSpec.F4))
            val key = generator.generateKeyPair().private as RSAPrivateCrtKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, sealingKey())
            val sealed = cipher.iv + cipher.doFinal(key.encoded)
            AtomicFiles.save(file, Base64.getEncoder().encodeToString(sealed))
            return key
        }

        private fun sealingKey(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            val spec =
                KeyGenParameterSpec
                    .Builder(ALIAS, purposes)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_BITS)
                    .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
                init(spec)
                generateKey()
            }
        }

        private fun selfSigned(key: RSAPrivateCrtKey): X509Certificate {
            val public =
                KeyFactory.getInstance("RSA").generatePublic(
                    java.security.spec.RSAPublicKeySpec(key.modulus, key.publicExponent),
                )
            val name = X500Name("CN=00")
            val holder =
                X509v3CertificateBuilder(
                    name,
                    BigInteger.ONE,
                    Date(0),
                    Date(NOT_AFTER_MS),
                    name,
                    SubjectPublicKeyInfo.getInstance(public.encoded),
                ).build(JcaContentSignerBuilder("SHA256withRSA").build(key))
            val factory = CertificateFactory.getInstance("X.509")
            return factory.generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
        }

        companion object {
            const val DIR = "shell"
            private const val TAG = "MCP:Shell"
            private const val NAME = "brooce@phone"
            private const val KEYSTORE = "AndroidKeyStore"
            private const val ALIAS = "shell_adbkey_seal"
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val IV = 12
            private const val TAG_BITS = 128
            private const val AES_BITS = 256
            private const val RSA_BITS = 2048
            private const val NOT_AFTER_MS = 2_461_449_600_000L
        }
    }

/** Offers the one key, for RSA. */
private class Keys(
    private val key: PrivateKey,
    private val certificate: X509Certificate,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(
        keyTypes: Array<out String>,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = ALIAS.takeIf { "RSA" in keyTypes }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        val chain = arrayOf(certificate)
        return chain.takeIf { alias == ALIAS }
    }

    override fun getPrivateKey(alias: String?): PrivateKey? = key.takeIf { alias == ALIAS }

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    private companion object {
        const val ALIAS = "key"
    }
}

/**
 * adbd presents a self-signed certificate of its own, so there is no chain to check. What is
 * relied on instead: the socket is the loopback address of this very phone, and adbd only lets
 * in a client whose key it was taught by a pairing the user did by hand.
 */
private object AnyPeer : X509ExtendedTrustManager() {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = Unit

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
