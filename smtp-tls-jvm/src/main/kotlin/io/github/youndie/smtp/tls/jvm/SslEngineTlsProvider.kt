package io.github.youndie.smtp.tls.jvm

import io.github.youndie.smtp.transport.ByteConnection
import io.github.youndie.smtp.transport.TlsConfig
import io.github.youndie.smtp.transport.TlsException
import io.github.youndie.smtp.transport.TlsProvider
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS on the JVM, through `SSLEngine`.
 *
 * `SSLEngine` is the JVM's own answer to the same problem OpenSSL solves with memory BIOs: it
 * transforms buffers and leaves the moving of bytes to the caller. That is exactly the shape
 * [TlsProvider] asks for, so no socket is involved here either.
 */
public object SslEngineTlsProvider : TlsProvider {
    override suspend fun handshake(
        connection: ByteConnection,
        config: TlsConfig,
    ): ByteConnection {
        val engine =
            buildContext(config).createSSLEngine(config.serverName, -1).apply {
                useClientMode = true
                sslParameters =
                    sslParameters.apply {
                        // SNI: a relay serving several domains answers with the wrong certificate
                        // without it.
                        serverNames = listOf(SNIHostName(config.serverName))

                        if (!config.dangerouslyDisableCertificateVerification) {
                            // The JVM's name check. "HTTPS" is the algorithm rfc7817.txt points at
                            // for mail as well, and setting it here makes a mismatch fail the
                            // handshake rather than turn into a result nobody reads.
                            endpointIdentificationAlgorithm = "HTTPS"
                        }
                    }
            }

        val session = SslEngineConnection(connection, engine)
        session.handshake()
        return session
    }

    private fun buildContext(config: TlsConfig): SSLContext {
        val context = SSLContext.getInstance("TLS")

        val trustManagers: Array<TrustManager>? =
            when {
                config.dangerouslyDisableCertificateVerification -> arrayOf(TrustEverything)
                config.caBundlePath != null -> trustManagersFor(config.caBundlePath!!)
                else -> null
            }

        context.init(null, trustManagers, null)
        return context
    }

    /** A trust store holding exactly the certificates of one PEM file. */
    private fun trustManagersFor(bundlePath: String): Array<TrustManager> {
        val file = File(bundlePath)
        if (!file.isFile) throw TlsException("The trust bundle $bundlePath does not exist")

        val certificates =
            file.inputStream().use { stream ->
                CertificateFactory.getInstance("X.509").generateCertificates(stream)
            }
        if (certificates.isEmpty()) throw TlsException("The trust bundle $bundlePath holds no certificates")

        val store =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { index, certificate ->
                    setCertificateEntry("ca-$index", certificate as X509Certificate)
                }
            }

        return TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers
    }

    /** Only reachable through the flag whose name says what it does. */
    private object TrustEverything : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/** The encrypted connection: `SSLEngine` transforms buffers, this class moves the bytes. */
internal class SslEngineConnection(
    private val underlying: ByteConnection,
    private val engine: SSLEngine,
) : ByteConnection {
    private val packetSize = engine.session.packetBufferSize
    private val applicationSize = engine.session.applicationBufferSize

    private var incomingCipher = ByteBuffer.allocate(packetSize).apply { limit(0) }
    private val outgoingCipher = ByteBuffer.allocate(packetSize)
    private var incomingPlain = ByteBuffer.allocate(applicationSize).apply { limit(0) }
    private val transferBuffer = ByteArray(packetSize)

    suspend fun handshake() {
        engine.beginHandshake()

        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    wrapAndSend(ByteBuffer.allocate(0))
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!unwrapOnce()) {
                        throw TlsException("The server closed the connection during the handshake")
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                }

                else -> {
                    return
                }
            }
        }
    }

    override suspend fun read(destination: ByteArray): Int {
        while (!incomingPlain.hasRemaining()) {
            if (!unwrapOnce()) return -1
        }

        val length = minOf(destination.size, incomingPlain.remaining())
        incomingPlain.get(destination, 0, length)
        return length
    }

    override suspend fun write(
        source: ByteArray,
        length: Int,
    ) {
        val plain = ByteBuffer.wrap(source, 0, length)
        while (plain.hasRemaining()) {
            wrapAndSend(plain)
        }
    }

    @Suppress(
        "ktlint:kapkan:swallowed-failure",
        "закрытие TLS по возможности: отказ здесь не должен вытеснить причину, по которой закрываемся",
        "ktlint:kapkan:cancellation-swallowed",
        "переброс отмены отсюда пропустил бы underlying.close() и оставил сокет незакрытым",
    )
    override suspend fun close() {
        runCatching {
            engine.closeOutbound()
            while (!engine.isOutboundDone) {
                wrapAndSend(ByteBuffer.allocate(0))
            }
        }
        underlying.close()
    }

    private suspend fun wrapAndSend(plain: ByteBuffer) {
        outgoingCipher.clear()
        val result =
            try {
                engine.wrap(plain, outgoingCipher)
            } catch (cause: SSLException) {
                throw TlsException("TLS wrap failed: ${cause.message}", cause)
            }

        when (result.status) {
            SSLEngineResult.Status.OK, SSLEngineResult.Status.CLOSED -> Unit
            else -> throw TlsException("TLS wrap returned ${result.status}")
        }

        outgoingCipher.flip()
        if (outgoingCipher.hasRemaining()) {
            val bytes = ByteArray(outgoingCipher.remaining())
            outgoingCipher.get(bytes)
            underlying.write(bytes, bytes.size)
        }

        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks()
    }

    /** Returns false at end of stream. */
    private suspend fun unwrapOnce(): Boolean {
        while (true) {
            if (!incomingCipher.hasRemaining()) {
                if (!fill()) return false
            }

            incomingPlain.clear()
            val result =
                try {
                    engine.unwrap(incomingCipher, incomingPlain)
                } catch (cause: SSLException) {
                    // A failed name or chain check surfaces here, and its message is the only place
                    // that says which of the two it was.
                    throw TlsException("TLS handshake failed: ${cause.message}", cause)
                }
            incomingPlain.flip()

            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks()
                    return true
                }

                // Not enough ciphertext for a whole record yet: keep what arrived and read more.
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    if (!fill()) return false
                }

                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    incomingPlain = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { limit(0) }
                }

                SSLEngineResult.Status.CLOSED -> {
                    return false
                }
            }
        }
    }

    /** Appends more ciphertext, keeping whatever partial record is already buffered. */
    private suspend fun fill(): Boolean {
        val leftover = incomingCipher.remaining()
        val room = ByteBuffer.allocate(maxOf(packetSize, leftover + packetSize))
        room.put(incomingCipher)

        val read = underlying.read(transferBuffer)
        if (read <= 0) return false

        room.put(transferBuffer, 0, read)
        room.flip()
        incomingCipher = room
        return true
    }

    private fun runDelegatedTasks() {
        while (true) {
            val task = engine.delegatedTask ?: return
            task.run()
        }
    }
}
