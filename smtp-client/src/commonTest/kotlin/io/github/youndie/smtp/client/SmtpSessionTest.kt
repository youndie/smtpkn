package io.github.youndie.smtp.client

import io.github.youndie.smtp.protocol.Mailbox
import io.github.youndie.smtp.protocol.SmtpProtocolException
import io.github.youndie.smtp.protocol.SmtpRefusedException
import io.github.youndie.smtp.testing.ScriptedTransport
import io.github.youndie.smtp.testing.scriptedTransport
import io.github.youndie.smtp.transport.ByteConnection
import io.github.youndie.smtp.transport.TlsConfig
import io.github.youndie.smtp.transport.TlsProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The session: phases, transactions and what a refusal turns into.
 *
 * Phase diagram — docs/api/protocol-smtp.md §3; the underlying rules are cited per test.
 */
class SmtpSessionTest {
    @Test
    fun `opening a session reads the greeting and identifies the client`() =
        runTest {
            val transport =
                scriptedTransport {
                    serverSays("220 smtp.example.com ESMTP")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250-PIPELINING", "250 SIZE 100")
                }

            val session = openSession(transport)

            assertTrue(session.capabilities.supportsPipelining)
            assertEquals(100L, session.capabilities.maxMessageSize)
            transport.assertScriptCompleted()
        }

    @Test
    fun `a server that does not understand EHLO gets HELO`() =
        runTest {
            // rfc5321.txt:1836: EHLO is the modern greeting; HELO stays as the fallback for
            // servers that predate ESMTP, and they answer 500 or 502.
            val transport =
                scriptedTransport {
                    serverSays("220 smtp.example.com")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("500 Command not recognized")
                    clientWrites("HELO client.example.com\r\n")
                    serverSays("250 smtp.example.com")
                }

            val session = openSession(transport)

            // HELO announces nothing, so no extension may be assumed.
            assertEquals(emptySet(), session.capabilities.keywords)
            assertFalse(session.capabilities.supportsPipelining)
            transport.assertScriptCompleted()
        }

    @Test
    fun `a negative greeting means no session`() =
        runTest {
            // rfc5321.txt:2642: 5xx is a permanent refusal; there is nothing to talk about.
            val transport = scriptedTransport { serverSays("554 No SMTP service here") }

            val failure = assertFailsWith<SmtpRefusedException> { openSession(transport) }

            assertTrue(failure.isPermanent)
        }

    @Test
    fun `sending a message walks MAIL then RCPT then DATA`() =
        runTest {
            val transport =
                scriptedTransport {
                    greeting()
                    clientWrites("MAIL FROM:<sender@example.com>\r\n")
                    serverSays("250 2.1.0 Ok")
                    clientWrites("RCPT TO:<rcpt@example.com>\r\n")
                    serverSays("250 2.1.5 Ok")
                    clientWrites("DATA\r\n")
                    serverSays("354 End data with <CRLF>.<CRLF>")
                    clientWrites("Subject: hi\r\n\r\nbody\r\n.\r\n")
                    serverSays("250 2.0.0 Ok: queued as 4B2C")
                }

            val result = openSession(transport).send(envelope("rcpt@example.com"), body())

            assertEquals(listOf(Mailbox.parse("rcpt@example.com")), result.accepted)
            assertEquals(emptyList(), result.rejected)
            assertEquals(250, result.acceptance?.code?.value)
            transport.assertScriptCompleted()
        }

    @Test
    fun `a rejected recipient is data rather than an exception`() =
        runTest {
            // A partial refusal must not be collapsed into an exception: the caller would lose the
            // knowledge of who did receive the message.
            val transport =
                scriptedTransport {
                    greeting()
                    clientWrites("MAIL FROM:<sender@example.com>\r\n")
                    serverSays("250 Ok")
                    clientWrites("RCPT TO:<good@example.com>\r\n")
                    serverSays("250 Ok")
                    clientWrites("RCPT TO:<bad@example.com>\r\n")
                    serverSays("550 5.1.1 <bad@example.com>: Recipient address rejected")
                    clientWrites("DATA\r\n")
                    serverSays("354 Go ahead")
                    clientWrites("Subject: hi\r\n\r\nbody\r\n.\r\n")
                    serverSays("250 Ok")
                }

            val result =
                openSession(transport).send(
                    envelope("good@example.com", "bad@example.com"),
                    body(),
                )

            assertEquals(listOf(Mailbox.parse("good@example.com")), result.accepted)
            assertEquals(1, result.rejected.size)
            assertEquals(
                550,
                result.rejected
                    .single()
                    .reply.code.value,
            )
            assertEquals(
                "5.1.1",
                result.rejected
                    .single()
                    .reply.enhancedStatus
                    .toString(),
            )
            transport.assertScriptCompleted()
        }

    @Test
    fun `with every recipient rejected the transaction is reset and DATA is never sent`() =
        runTest {
            val transport =
                scriptedTransport {
                    greeting()
                    clientWrites("MAIL FROM:<sender@example.com>\r\n")
                    serverSays("250 Ok")
                    clientWrites("RCPT TO:<bad@example.com>\r\n")
                    serverSays("550 Recipient address rejected")
                    clientWrites("RSET\r\n")
                    serverSays("250 Ok")
                }

            val result = openSession(transport).send(envelope("bad@example.com"), body())

            assertEquals(emptyList(), result.accepted)
            assertEquals(1, result.rejected.size)
            assertNull(result.acceptance, "nothing was sent, so nothing was accepted")
            transport.assertScriptCompleted()
        }

    @Test
    fun `a refused MAIL FROM is a permanent failure`() =
        runTest {
            val transport =
                scriptedTransport {
                    greeting()
                    clientWrites("MAIL FROM:<sender@example.com>\r\n")
                    serverSays("550 5.7.1 Sender address rejected")
                }

            val failure =
                assertFailsWith<SmtpRefusedException> {
                    openSession(transport).send(envelope("rcpt@example.com"), body())
                }

            assertTrue(failure.isPermanent)
            assertFalse(failure.isTransient)
            assertEquals("MAIL FROM", failure.command)
        }

    @Test
    fun `a 4xx refusal is transient and worth retrying`() =
        runTest {
            // rfc5321.txt:2642: 4xx is a transient negative completion — the same request may
            // succeed later, and the caller has to be able to tell the two apart.
            val transport =
                scriptedTransport {
                    greeting()
                    clientWrites("MAIL FROM:<sender@example.com>\r\n")
                    serverSays("451 4.3.0 Temporary system problem")
                }

            val failure =
                assertFailsWith<SmtpRefusedException> {
                    openSession(transport).send(envelope("rcpt@example.com"), body())
                }

            assertTrue(failure.isTransient)
            assertFalse(failure.isPermanent)
        }

    @Test
    fun `two messages go through one connection`() =
        runTest {
            val transport =
                scriptedTransport {
                    greeting()
                    transaction("first@example.com")
                    transaction("second@example.com")
                }

            val session = openSession(transport)
            session.send(envelope("first@example.com"), body())
            session.send(envelope("second@example.com"), body())

            transport.assertScriptCompleted()
        }

    @Test
    fun `reset clears the transaction without ending the session`() =
        runTest {
            val transport =
                scriptedTransport {
                    greeting()
                    clientWrites("RSET\r\n")
                    serverSays("250 Ok")
                }

            openSession(transport).reset()

            transport.assertScriptCompleted()
        }

    @Test
    fun `quit says goodbye and closes the transport`() =
        runTest {
            val transport =
                scriptedTransport {
                    greeting()
                    clientWrites("QUIT\r\n")
                    serverSays("221 Bye")
                }

            openSession(transport).quit()

            assertTrue(transport.isClosed)
            transport.assertScriptCompleted()
        }

    @Test
    fun `a silent server trips the greeting timeout`() =
        runTest {
            // rfc5321.txt:3623: waiting for the 220 greeting is capped at 5 minutes. Without a
            // timeout a dead server holds the caller forever.
            val transport = scriptedTransport { serverHangs() }

            assertFailsWith<SmtpTimeoutException> { openSession(transport) }
        }

    @Test
    fun `timeouts can be shortened by the caller`() =
        runTest {
            // The RFC values are minimums for interoperability; a caller that knows its relay may
            // pick tighter ones, and that has to be its decision rather than ours.
            val transport = scriptedTransport { serverHangs() }

            val failure =
                assertFailsWith<SmtpTimeoutException> {
                    openSession(transport, SmtpTimeouts(greeting = 1.seconds))
                }

            assertEquals(1.seconds, failure.limit)
        }

    @Test
    fun `STARTTLS discards everything learned before the handshake`() =
        runTest {
            // rfc3207.txt:210: "The client MUST discard any knowledge obtained from the server,
            // such as the list of SMTP service extensions, which was not obtained from the TLS
            // negotiation itself."
            val transport =
                scriptedTransport {
                    serverSays("220 smtp.example.com")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 STARTTLS")
                    clientWrites("STARTTLS\r\n")
                    serverSays("220 Ready to start TLS")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 AUTH PLAIN")
                }

            val session = openSession(transport)
            val beforeHandshake = session.capabilities
            var upgraded = false

            session.startTls { upgraded = true }

            assertTrue(upgraded, "the transport was handed over for the handshake")
            assertEquals(setOf("AUTH"), session.capabilities.keywords)

            // The old value is not merely outdated — using it is an error, because a client that
            // trusts pre-handshake extensions can be talked into a downgrade.
            assertFailsWith<SmtpProtocolException> { beforeHandshake.supportsStartTls }

            transport.assertScriptCompleted()
        }

    @Test
    fun `STARTTLS records that the connection is encrypted`() =
        runTest {
            // rfc3207.txt:177: "After the TLS handshake has been completed, both parties MUST
            // immediately decide whether or not to continue based on the authentication and
            // privacy achieved." Deciding takes remembering, and everything downstream that asks
            // whether the channel is protected — AUTH above all — reads this flag.
            val transport =
                scriptedTransport {
                    serverSays("220 smtp.example.com")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 STARTTLS")
                    clientWrites("STARTTLS\r\n")
                    serverSays("220 Ready to start TLS")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 AUTH PLAIN")
                }

            val session = openSession(transport)
            assertFalse(session.isEncrypted, "nothing has been negotiated yet")

            session.startTls { /* the caller's handshake; here there is no byte layer to swap */ }

            assertTrue(session.isEncrypted)
            transport.assertScriptCompleted()
        }

    @Test
    fun `STARTTLS over a transport that cannot swap its byte layer is refused`() =
        runTest {
            // A scripted transport has no byte layer to replace. Failing here beats pretending the
            // upgrade happened and continuing in the clear.
            val transport =
                scriptedTransport {
                    greeting()
                    clientWrites("STARTTLS\r\n")
                    serverSays("220 Ready to start TLS")
                }

            val session = openSession(transport)

            assertFailsWith<SmtpProtocolException> {
                session.startTls(
                    provider =
                        object : TlsProvider {
                            override suspend fun handshake(
                                connection: ByteConnection,
                                config: TlsConfig,
                            ): ByteConnection = error("never reached")
                        },
                    config = TlsConfig(serverName = "smtp.example.com"),
                )
            }
        }

    private fun envelope(vararg recipients: String) =
        Envelope(
            sender = Mailbox.parse("sender@example.com"),
            recipients = recipients.map(Mailbox::parse),
        )

    private fun body() = listOf("Subject: hi", "", "body")

    private suspend fun openSession(
        transport: ScriptedTransport,
        timeouts: SmtpTimeouts = SmtpTimeouts(),
    ) = openSmtpSession(
        transport = transport,
        config = SmtpClientConfig(clientIdentity = "client.example.com", timeouts = timeouts),
    )

    private companion object {
        /** The opening every test needs and no test is about. */
        fun ScriptedTransport.Builder.greeting() {
            serverSays("220 smtp.example.com")
            clientWrites("EHLO client.example.com\r\n")
            // No PIPELINING here on purpose: these tests are about the step-by-step flow, and a
            // server offering it would make the client group the commands instead.
            serverSays("250 smtp.example.com")
        }

        /** One accepted message for a single recipient. */
        fun ScriptedTransport.Builder.transaction(recipient: String) {
            clientWrites("MAIL FROM:<sender@example.com>\r\n")
            serverSays("250 Ok")
            clientWrites("RCPT TO:<$recipient>\r\n")
            serverSays("250 Ok")
            clientWrites("DATA\r\n")
            serverSays("354 Go ahead")
            clientWrites("Subject: hi\r\n\r\nbody\r\n.\r\n")
            serverSays("250 Ok")
        }
    }
}
