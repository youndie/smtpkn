package io.github.youndie.smtp.client

import io.github.youndie.smtp.protocol.Mailbox
import io.github.youndie.smtp.testing.FakeSmtpServer
import io.github.youndie.smtp.transport.ktor.connectSmtp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * How much one connection carries, and whether it survives it.
 *
 * This is a measurement rather than a gate: the numbers depend on the machine and the server, so
 * failing a build on them would only produce noise. What it does check is the part that is not a
 * number — that message number N behaves like message number one, and that the session is still
 * usable afterwards.
 *
 * It runs against the in-process fake, so it measures the client rather than a network. That is
 * the honest scope: a number taken against a container on the same machine would look like a
 * throughput figure for a real relay, and it is not one.
 */
class ConnectionLoadTest {
    @Test
    fun `one connection carries a thousand messages and stays usable`() =
        runTest {
            withContext(Dispatchers.Default) {
                val server = FakeSmtpServer()
                server.start()

                try {
                    val transport = connectSmtp("127.0.0.1", server.port)
                    val session =
                        openSmtpSession(
                            transport = transport,
                            config = SmtpClientConfig(clientIdentity = "load.test"),
                        )

                    val started = TimeSource.Monotonic.markNow()
                    repeat(MESSAGES) { index -> send(session, index) }
                    val elapsed = started.elapsedNow()

                    assertEquals(MESSAGES, server.received.size)

                    // The last message must be whole: a connection that degrades under load
                    // usually does it by losing the tail of a body, not by failing outright.
                    assertEquals(
                        "Subject: message ${MESSAGES - 1}",
                        server.received
                            .last()
                            .body
                            .first(),
                    )
                    assertEquals(listOf("Subject: message ${MESSAGES - 1}", "", "body"), server.received.last().body)

                    // And it is still a working session, not just a socket that has not closed yet.
                    session.reset()
                    send(session, MESSAGES)
                    assertEquals(MESSAGES + 1, server.received.size)

                    val perSecond = MESSAGES * 1000.0 / elapsed.inWholeMilliseconds.coerceAtLeast(1)
                    println(
                        "LOAD: $MESSAGES messages over one connection in ${elapsed.inWholeMilliseconds} ms " +
                            "(${perSecond.toInt()}/s) against the in-process fake",
                    )

                    session.quit()
                } finally {
                    server.stop()
                }
            }
        }

    @Test
    fun `a connection idle between messages is still good`() =
        runTest {
            // Servers close idle connections on their own schedule — Postfix defaults to five
            // minutes — so the client cannot promise a connection stays open, only that it does
            // not break it itself. This checks the client's half.
            withContext(Dispatchers.Default) {
                val server = FakeSmtpServer()
                server.start()

                try {
                    val transport = connectSmtp("127.0.0.1", server.port)
                    val session =
                        openSmtpSession(
                            transport = transport,
                            config = SmtpClientConfig(clientIdentity = "load.test"),
                        )

                    send(session, 0)
                    repeat(IDLE_PROBES) { session.reset() }
                    send(session, 1)

                    assertEquals(2, server.received.size)
                    assertTrue(session.capabilities.keywords.isNotEmpty())

                    session.quit()
                } finally {
                    server.stop()
                }
            }
        }

    private suspend fun send(
        session: SmtpSession,
        index: Int,
    ) {
        session.send(
            envelope =
                Envelope(
                    sender = Mailbox.parse("sender@example.com"),
                    recipients = listOf(Mailbox.parse("rcpt@example.com")),
                ),
            body = listOf("Subject: message $index", "", "body"),
        )
    }

    private companion object {
        const val MESSAGES = 1000
        const val IDLE_PROBES = 50
    }
}
