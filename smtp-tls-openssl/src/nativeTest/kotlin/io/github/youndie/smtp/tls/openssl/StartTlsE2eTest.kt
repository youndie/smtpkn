package io.github.youndie.smtp.tls.openssl

import io.github.youndie.smtp.client.Envelope
import io.github.youndie.smtp.client.SmtpClientConfig
import io.github.youndie.smtp.client.openSmtpSession
import io.github.youndie.smtp.protocol.Mailbox
import io.github.youndie.smtp.transport.TlsConfig
import io.github.youndie.smtp.transport.ktor.connectSmtp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The submission scenario of port 587: plain connection, `STARTTLS`, then the message.
 *
 * Beyond "does it encrypt", this checks the rule that costs security when skipped: after the
 * handshake the extension list learned earlier must be gone (`docs/rfc/rfc3207.txt:210`).
 */
class StartTlsE2eTest {
    @Test
    fun `a message goes out over STARTTLS`() =
        withServer { host, port, ca ->
            val transport = connectSmtp(host, port)
            try {
                val session =
                    openSmtpSession(
                        transport = transport,
                        config = SmtpClientConfig(clientIdentity = "kmp-smtp-client.test"),
                    )

                assertTrue(session.capabilities.supportsStartTls, "the server offers STARTTLS")
                val beforeHandshake = session.capabilities

                session.startTls(
                    provider = OpenSslTlsProvider,
                    config = TlsConfig(serverName = SERVER_NAME, caBundlePath = ca),
                )

                // rfc3207.txt:210 in the field: the pre-handshake list is not merely outdated.
                assertFailsWith<Throwable> { beforeHandshake.supportsStartTls }

                // The scripted suite cannot say this: only a real provider over a real connection
                // shows that what the session records matches what the socket is actually doing.
                // AUTH reads this, and read it wrong until the flag was assigned.
                assertTrue(session.isEncrypted, "the session records the completed handshake")

                val result =
                    session.send(
                        envelope =
                            Envelope(
                                sender = Mailbox.parse("sender@example.com"),
                                recipients = listOf(Mailbox.parse("recipient@example.com")),
                            ),
                        body = listOf("Subject: over STARTTLS", "", "encrypted body"),
                    )

                assertEquals(1, result.accepted.size)
                assertEquals(250, result.acceptance?.code?.value)
                session.quit()
            } finally {
                transport.close()
            }
        }

    private fun withServer(block: suspend (host: String, port: Int, ca: String) -> Unit) =
        runTest {
            val host = environment("SMTP_TLS_E2E_HOST")
            val ca = environment("SMTP_TLS_E2E_CA")

            if (host == null || ca == null) {
                if (environment("SMTP_E2E_REQUIRED") != null) {
                    fail("SMTP_E2E_REQUIRED is set but SMTP_TLS_E2E_HOST/SMTP_TLS_E2E_CA are not")
                }
                println("SKIPPED StartTlsE2eTest: run `docker compose up -d` and set SMTP_TLS_E2E_HOST")
                return@runTest
            }

            // STARTTLS runs on the plain submission port, not on the implicit-TLS one.
            val port = environment("SMTP_E2E_PORT")?.takeIf { it.isNotBlank() }?.toInt() ?: DEFAULT_PORT
            withContext(Dispatchers.Default) { block(host, port, ca) }
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun environment(name: String): String? = getenv(name)?.toKString()

    private companion object {
        const val DEFAULT_PORT = 1025
        const val SERVER_NAME = "localhost"
    }
}
