package io.github.sunsind.sunlith.device

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.sunsind.sunlith.device.DeviceLinkStore.hex
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.ECKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Self-signed TLS identity whose EC P-256 key never leaves AndroidKeyStore. Clients pin the SHA-256 of the DER certificate. */
internal object DeviceLinkTls {
    private const val ALIAS = "sunlith_device_link_tls"
    private const val PROVIDER = "AndroidKeyStore"
    @Volatile private var cached: Pair<PrivateKey, X509Certificate>? = null

    fun fingerprint(): String = DeviceLinkStore.sha256(identity().second.encoded).hex()

    fun sslContext(): SSLContext {
        identity()
        return SSLContext.getInstance("TLS").apply { init(arrayOf<KeyManager>(LinkKeyManager), null, SecureRandom()) }
    }

    fun protocols(supported: Array<String>): Array<String> =
        arrayOf("TLSv1.3", "TLSv1.2").filter { it in supported }.toTypedArray().also { check(it.isNotEmpty()) { "TLS 1.2 is not available." } }

    @Synchronized
    fun identity(): Pair<PrivateKey, X509Certificate> {
        cached?.takeIf { usable(it.second) }?.let { return it }
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        load(store)?.let { cached = it; return it }
        runCatching { store.deleteEntry(ALIAS) }
        val now = System.currentTimeMillis()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=Sunlith phone link"))
                .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - DAY))
                .setCertificateNotAfter(Date(now + 10 * 365 * DAY))
                .build())
        }.generateKeyPair()
        val created = load(KeyStore.getInstance(PROVIDER).apply { load(null) }) ?: error("The TLS key could not be created.")
        cached = created
        return created
    }

    private fun load(store: KeyStore): Pair<PrivateKey, X509Certificate>? {
        val key = runCatching { store.getKey(ALIAS, null) as? PrivateKey }.getOrNull() ?: return null
        val certificate = runCatching { store.getCertificate(ALIAS) as? X509Certificate }.getOrNull() ?: return null
        return (key to certificate).takeIf { usable(certificate) && key is ECKey }
    }

    private fun usable(certificate: X509Certificate): Boolean =
        runCatching { certificate.checkValidity(Date(System.currentTimeMillis() + 30 * DAY)) }.isSuccess

    private object LinkKeyManager : X509ExtendedKeyManager() {
        private fun alias(keyType: String?) = ALIAS.takeIf { keyType == null || keyType.startsWith("EC") }
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias(keyType)
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias(keyType)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = alias(keyType)?.let { arrayOf(it) }
        override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(identity().second) else null
        override fun getPrivateKey(alias: String?) = if (alias == ALIAS) identity().first else null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    private const val DAY = 24L * 60 * 60 * 1000
}
