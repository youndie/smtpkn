package io.github.youndie.smtp.tls.openssl

import io.github.youndie.smtp.transport.ByteConnection
import io.github.youndie.smtp.transport.TlsConfig
import io.github.youndie.smtp.transport.TlsException
import io.github.youndie.smtp.transport.TlsProvider
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import openssl.BIO
import openssl.BIO_free
import openssl.BIO_new
import openssl.BIO_read
import openssl.BIO_s_mem
import openssl.BIO_write
import openssl.ERR_clear_error
import openssl.ERR_error_string_n
import openssl.ERR_get_error
import openssl.OPENSSL_init_ssl
import openssl.OpenSSL_version_num
import openssl.SSL
import openssl.SSL_CTX
import openssl.SSL_CTX_free
import openssl.SSL_CTX_load_verify_locations
import openssl.SSL_CTX_new
import openssl.SSL_CTX_set_default_verify_paths
import openssl.SSL_CTX_set_verify
import openssl.SSL_connect
import openssl.SSL_free
import openssl.SSL_get_error
import openssl.SSL_get_verify_result
import openssl.SSL_new
import openssl.SSL_read
import openssl.SSL_set1_host
import openssl.SSL_set_bio
import openssl.SSL_set_connect_state
import openssl.SSL_shutdown
import openssl.SSL_write
import openssl.TLS_client_method
import openssl.X509_verify_cert_error_string
import openssl.kmp_bio_pending
import openssl.kmp_ssl_ctx_set_min_proto_version
import openssl.kmp_ssl_error_want_read
import openssl.kmp_ssl_error_want_write
import openssl.kmp_ssl_error_zero_return
import openssl.kmp_ssl_set_tlsext_host_name
import openssl.kmp_ssl_verify_none
import openssl.kmp_ssl_verify_peer
import openssl.kmp_tls1_2_version

/**
 * TLS through OpenSSL 3, driven over memory BIOs.
 *
 * No file descriptor is handed to OpenSSL, and that is not a stylistic choice: a Ktor socket does
 * not expose one (docs/research/research-architecture.md, consequence 3 of 1.1). Ciphertext is
 * pumped between the library and the underlying [ByteConnection] by hand, which costs a few
 * hundred lines and buys two things — `STARTTLS` becomes an ordinary swap of the layer below, and
 * the same code works over any transport, including an in-memory one.
 */
@OptIn(ExperimentalForeignApi::class)
public object OpenSslTlsProvider : TlsProvider {
    /**
     * The OpenSSL version this process is actually linked against.
     *
     * Worth reading before blaming the handshake: 1.x and 3.x are not ABI compatible, and a build
     * that found 3.x headers can still load a 1.x library at runtime.
     */
    public val libraryVersion: String
        get() {
            val packed = OpenSSL_version_num()
            val major = (packed shr 28) and 0xFu
            val minor = (packed shr 20) and 0xFFu
            val patch = (packed shr 4) and 0xFFu
            return "$major.$minor.$patch"
        }

    override suspend fun handshake(
        connection: ByteConnection,
        config: TlsConfig,
    ): ByteConnection {
        requireSupportedLibrary()

        OPENSSL_init_ssl(0u, null)

        val method = TLS_client_method() ?: throw TlsException("OpenSSL has no TLS client method")
        val ctx = SSL_CTX_new(method) ?: throw TlsException("SSL_CTX_new failed: ${lastError()}")

        // Ownership is handed over exactly once, at the moment OpenSslConnection is built. Before
        // that everything is freed here; after that only the connection frees anything. Getting
        // this wrong is not a leak but a double free, and a double free kills the process instead
        // of failing a test.
        var ssl: CPointer<SSL>? = null
        var readSide: CPointer<BIO>? = null

        val session =
            try {
                configure(ctx, config)

                ssl = SSL_new(ctx) ?: throw TlsException("SSL_new failed: ${lastError()}")

                val incoming = BIO_new(BIO_s_mem()) ?: throw TlsException("BIO_new failed: ${lastError()}")
                readSide = incoming
                val outgoing = BIO_new(BIO_s_mem()) ?: throw TlsException("BIO_new failed: ${lastError()}")

                // From here the SSL object owns both BIOs and frees them with itself.
                SSL_set_bio(ssl, incoming, outgoing)
                readSide = null
                SSL_set_connect_state(ssl)
                applyServerName(ssl, config)

                OpenSslConnection(
                    underlying = connection,
                    ctx = ctx,
                    ssl = ssl,
                    incoming = incoming,
                    outgoing = outgoing,
                    verifyPeer = !config.dangerouslyDisableCertificateVerification,
                )
            } catch (cause: Throwable) {
                readSide?.let { BIO_free(it) }
                ssl?.let { SSL_free(it) }
                SSL_CTX_free(ctx)
                throw cause
            }

        try {
            session.handshake()
        } catch (cause: Throwable) {
            session.closeResources()
            throw cause
        }
        return session
    }

    private fun configure(
        ctx: CPointer<SSL_CTX>,
        config: TlsConfig,
    ) {
        // rfc8314.txt: anything below TLS 1.2 is not to be offered any more.
        kmp_ssl_ctx_set_min_proto_version(ctx, kmp_tls1_2_version())

        if (config.dangerouslyDisableCertificateVerification) {
            SSL_CTX_set_verify(ctx, kmp_ssl_verify_none(), null)
            return
        }

        SSL_CTX_set_verify(ctx, kmp_ssl_verify_peer(), null)

        val bundle = config.caBundlePath
        val loaded =
            if (bundle == null) {
                SSL_CTX_set_default_verify_paths(ctx)
            } else {
                SSL_CTX_load_verify_locations(ctx, bundle, null)
            }

        if (loaded != 1) {
            throw TlsException(
                "Could not load the trust store (${bundle ?: "system default"}): ${lastError()}",
            )
        }
    }

    private fun applyServerName(
        ssl: CPointer<SSL>,
        config: TlsConfig,
    ) {
        // SNI: without it a relay serving several domains answers with the wrong certificate.
        kmp_ssl_set_tlsext_host_name(ssl, config.serverName)

        if (config.dangerouslyDisableCertificateVerification) return

        // The name check itself (docs/rfc/rfc7817.txt, docs/rfc/rfc9525.txt). Done by OpenSSL as
        // part of chain verification, so a mismatch fails the handshake rather than being
        // reported afterwards and forgotten.
        if (SSL_set1_host(ssl, config.serverName) != 1) {
            throw TlsException("Rejected server name '${config.serverName}': ${lastError()}")
        }
    }

    private fun requireSupportedLibrary() {
        val major = (OpenSSL_version_num() shr 28) and 0xFu
        if (major < 3u) {
            throw TlsException(
                "OpenSSL 3 is required, the process is linked against $libraryVersion. " +
                    "Versions 1.x and 3.x are not ABI compatible.",
            )
        }
    }

    internal fun lastError(): String {
        val code = ERR_get_error()
        if (code == 0uL) return "no OpenSSL error recorded"

        return memScoped {
            val buffer = allocArray<ByteVar>(ERROR_BUFFER)
            ERR_error_string_n(code, buffer, ERROR_BUFFER.toULong())
            buffer.toKString()
        }
    }

    private const val ERROR_BUFFER = 256
}

/** The encrypted connection: everything below happens on memory BIOs, never on a descriptor. */
@OptIn(ExperimentalForeignApi::class)
internal class OpenSslConnection(
    private val underlying: ByteConnection,
    private val ctx: CPointer<SSL_CTX>,
    private val ssl: CPointer<SSL>,
    private val incoming: CPointer<BIO>,
    private val outgoing: CPointer<BIO>,
    private val verifyPeer: Boolean,
) : ByteConnection {
    private val pump = ByteArray(PUMP_BUFFER)
    private var closed = false

    suspend fun handshake() {
        while (true) {
            // The error queue is per thread and survives across calls. Without clearing it first,
            // a failure reported here can be somebody else's, from an operation that already
            // returned — which is exactly how a working connection gets blamed for a certificate
            // that was rejected in a previous test.
            ERR_clear_error()
            val result = SSL_connect(ssl)
            if (result == 1) {
                drainOutgoing()
                verify()
                return
            }

            when (SSL_get_error(ssl, result)) {
                kmp_ssl_error_want_read() -> {
                    drainOutgoing()
                    if (!feedIncoming()) throw TlsException("The server closed the connection during the handshake")
                }

                kmp_ssl_error_want_write() -> {
                    drainOutgoing()
                }

                else -> {
                    throw TlsException("TLS handshake failed: ${describeFailure()}")
                }
            }
        }
    }

    override suspend fun read(destination: ByteArray): Int {
        while (true) {
            ERR_clear_error()
            val read = destination.usePinned { SSL_read(ssl, it.addressOf(0), destination.size) }
            if (read > 0) return read

            when (SSL_get_error(ssl, read)) {
                kmp_ssl_error_zero_return() -> {
                    return -1
                }

                kmp_ssl_error_want_read() -> {
                    drainOutgoing()
                    if (!feedIncoming()) return -1
                }

                kmp_ssl_error_want_write() -> {
                    drainOutgoing()
                }

                else -> {
                    throw TlsException("TLS read failed: ${OpenSslTlsProvider.lastError()}")
                }
            }
        }
    }

    override suspend fun write(
        source: ByteArray,
        length: Int,
    ) {
        var sent = 0
        while (sent < length) {
            ERR_clear_error()
            val written = source.usePinned { SSL_write(ssl, it.addressOf(sent), length - sent) }
            if (written > 0) {
                sent += written
                drainOutgoing()
                continue
            }

            when (SSL_get_error(ssl, written)) {
                kmp_ssl_error_want_read() -> {
                    drainOutgoing()
                    if (!feedIncoming()) throw TlsException("The server closed the connection while writing")
                }

                kmp_ssl_error_want_write() -> {
                    drainOutgoing()
                }

                else -> {
                    throw TlsException("TLS write failed: ${OpenSslTlsProvider.lastError()}")
                }
            }
        }
    }

    @Suppress(
        "ktlint:kapkan:swallowed-failure",
        "закрытие TLS по возможности: отказ здесь не должен вытеснить причину, по которой закрываемся",
        "ktlint:kapkan:cancellation-swallowed",
        "переброс отмены отсюда пропустил бы underlying.close() и оставил сокет незакрытым",
    )
    override suspend fun close() {
        if (closed) return
        SSL_shutdown(ssl)
        runCatching { drainOutgoing() }
        closeResources()
        underlying.close()
    }

    fun closeResources() {
        if (closed) return
        closed = true
        // SSL_free also frees both BIOs handed to SSL_set_bio.
        SSL_free(ssl)
        SSL_CTX_free(ctx)
    }

    /**
     * Verification result, read explicitly so that a failure names the reason.
     *
     * Skipped entirely when the caller asked for no verification: `SSL_VERIFY_NONE` lets the
     * handshake finish but still records why the certificate would have been rejected, so
     * checking here regardless would quietly make the escape hatch useless.
     */
    private fun verify() {
        if (!verifyPeer) return

        val result = SSL_get_verify_result(ssl)
        if (result != 0L) {
            val reason = X509_verify_cert_error_string(result)?.toKString() ?: "unknown reason"
            throw TlsException("The server certificate was rejected: $reason")
        }
    }

    /** Moves ciphertext OpenSSL produced into the socket. */
    private suspend fun drainOutgoing() {
        while (kmp_bio_pending(outgoing) > 0) {
            val read = pump.usePinned { BIO_read(outgoing, it.addressOf(0), pump.size) }
            if (read <= 0) return
            underlying.write(pump, read)
        }
    }

    /** Moves ciphertext from the socket into OpenSSL. Returns false at end of stream. */
    private suspend fun feedIncoming(): Boolean {
        val read = underlying.read(pump)
        if (read <= 0) return false

        var offset = 0
        while (offset < read) {
            val written =
                pump.usePinned { pinned ->
                    BIO_write(incoming, pinned.addressOf(offset), read - offset)
                }
            if (written <= 0) throw TlsException("OpenSSL refused ciphertext: ${OpenSslTlsProvider.lastError()}")
            offset += written
        }
        return true
    }

    private fun describeFailure(): String {
        val verifyResult = if (verifyPeer) SSL_get_verify_result(ssl) else 0L
        if (verifyResult != 0L) {
            val reason = X509_verify_cert_error_string(verifyResult)?.toKString() ?: "unknown reason"
            return "certificate rejected: $reason"
        }
        return OpenSslTlsProvider.lastError()
    }

    private companion object {
        const val PUMP_BUFFER = 16384
    }
}
