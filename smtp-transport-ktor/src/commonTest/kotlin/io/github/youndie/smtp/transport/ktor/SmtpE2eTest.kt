package io.github.youndie.smtp.transport.ktor

import io.github.youndie.smtp.protocol.Capabilities
import io.github.youndie.smtp.protocol.MailData
import io.github.youndie.smtp.protocol.Mailbox
import io.github.youndie.smtp.protocol.SmtpCommand
import io.github.youndie.smtp.protocol.SmtpReply
import io.github.youndie.smtp.protocol.SmtpReplyReader
import io.github.youndie.smtp.transport.SmtpTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A whole conversation with a real SMTP server from `docker-compose.yml`.
 *
 * Everything built so far meets the wire here: the reply reader, the command serialiser, the EHLO
 * parser and dot-stuffing. Unit tests answer "does the code match the RFC as I read it"; this one
 * answers "does a server agree".
 *
 * Start the server with `docker compose up -d` and run with `SMTP_E2E_HOST=127.0.0.1`.
 */
class SmtpE2eTest {
    @Test
    fun `a real server accepts a message`() =
        runTest {
            val host = e2eHostOrSkip() ?: return@runTest
            val port = environmentVariable("SMTP_E2E_PORT")?.takeIf { it.isNotBlank() }?.toInt() ?: DEFAULT_PORT
            val recipient = environmentVariable("SMTP_E2E_RECIPIENT")?.takeIf { it.isNotBlank() } ?: DEFAULT_RECIPIENT
            // Unique per run: the server keeps what earlier runs sent, and a fixed subject
            // would make the search find somebody else's message.
            val subject = "kmp-smtp-client e2e ${nextRunId()}"

            withContext(Dispatchers.Default) {
                val transport = connectSmtp(host, port)
                val session = Conversation(transport)

                try {
                    val greeting = session.read()
                    assertEquals(220, greeting.code.value, "greeting")

                    val capabilities = Capabilities.parse(session.exchange(SmtpCommand.Ehlo(CLIENT_IDENTITY)))
                    assertTrue(capabilities.greeting.isNotEmpty(), "the server names itself in its EHLO reply")

                    assertEquals(250, session.exchange(SmtpCommand.MailFrom(Mailbox.parse(SENDER))).code.value)
                    assertEquals(250, session.exchange(SmtpCommand.RcptTo(Mailbox.parse(recipient))).code.value)
                    assertEquals(354, session.exchange(SmtpCommand.Data).code.value)

                    transport.write(MailData.encode(message(recipient, subject)))
                    val accepted = session.read()
                    assertEquals(250, accepted.code.value, "the server accepted the message")

                    assertEquals(221, session.exchange(SmtpCommand.Quit).code.value)

                    verifyStoredMessage(host, subject)
                } finally {
                    transport.close()
                }
            }
        }

    /**
     * Asks the server what it actually stored.
     *
     * A 250 says the server took the message, not that it took *this* message. The check that
     * matters is the line holding a single period: if dot-stuffing were wrong it would either end
     * the message early or arrive doubled, and both look like success on the wire.
     *
     * Skipped where there is no API to ask — Postfix has none, and the E2E runs against it too.
     */
    private suspend fun verifyStoredMessage(
        host: String,
        subject: String,
    ) {
        // A variable set to an empty string is how a workflow says "not this run". Blank has to
        // mean absent, or the check dies on "".toInt() instead of being skipped.
        val apiPort = environmentVariable("SMTP_E2E_API_PORT")?.takeIf { it.isNotBlank() }?.toInt() ?: return

        val api = HttpClient(CIO)
        val raw =
            try {
                val listing =
                    api
                        .get("http://$host:$apiPort/api/v1/search") {
                            url { parameters.append("query", subject) }
                        }.bodyAsText()

                val id =
                    Regex("\"ID\":\"([^\"]+)\"").find(listing)?.groupValues?.get(1)
                        ?: fail("the server stored no message with subject '$subject': $listing")

                api.get("http://$host:$apiPort/api/v1/message/$id/raw").bodyAsText()
            } finally {
                api.close()
            }

        assertTrue(raw.contains("before the period"), "the body was truncated: $raw")
        assertTrue(raw.contains("after the period"), "the message ended at the lone period: $raw")
        assertTrue(raw.contains("\r\n.\r\n") || raw.contains("\n.\n"), "the lone period did not survive: $raw")
        assertTrue(!raw.contains("\r\n..\r\n"), "the period arrived doubled: $raw")
    }

    /**
     * The body carries a line consisting of a single period.
     *
     * That is the point of the test rather than decoration: without dot-stuffing
     * (`docs/rfc/rfc5321.txt:3423`) the message would end right there, and the server would read
     * the remaining lines as commands — so the reply to the body would not be 250.
     */
    private fun message(
        recipient: String,
        subject: String,
    ): List<String> =
        listOf(
            "From: <$SENDER>",
            "To: <$recipient>",
            "Subject: $subject",
            "",
            "before the period",
            ".",
            "after the period",
        )

    /** Drives one connection: writes a command, collects the reply the reader assembles. */
    private class Conversation(
        private val transport: SmtpTransport,
    ) {
        private val reader = SmtpReplyReader()

        suspend fun read(): SmtpReply {
            while (true) {
                reader.feed(transport.readLine())?.let { return it }
            }
        }

        suspend fun exchange(command: SmtpCommand): SmtpReply {
            transport.write(command.encode())
            return read()
        }
    }

    private companion object {
        const val DEFAULT_PORT = 1025
        const val CLIENT_IDENTITY = "kmp-smtp-client.test"
        const val SENDER = "sender@example.com"

        /**
         * The default recipient, overridable through the environment.
         *
         * Postfix refuses example.com with 556 nullMX — the domain says outright that it takes no
         * mail — while Mailpit accepts it without a word. Running the same test against both is
         * the point, so the address has to be a parameter.
         */
        const val DEFAULT_RECIPIENT = "recipient@example.com"

        /**
         * Something different on every run.
         *
         * The clock is enough: the servers keep what earlier runs sent, and two runs in the same
         * millisecond would need the test to be started twice by the same command.
         */
        @Suppress(
            "ktlint:kapkan:wall-clock",
            "фикстура теста строит момент относительно сейчас",
        )
        fun nextRunId(): String =
            kotlin.time.Clock.System
                .now()
                .toEpochMilliseconds()
                .toString(36)

        /**
         * Without a server the test cannot run, and `kotlin.test` has no way to report a skip.
         *
         * So the absence of the variable is announced rather than passed over in silence, and in
         * CI — where `SMTP_E2E_REQUIRED` is set — it is a failure: a gate that quietly disables
         * itself is indistinguishable from a gate that passes.
         */
        fun e2eHostOrSkip(): String? {
            val host = environmentVariable("SMTP_E2E_HOST")?.takeIf { it.isNotBlank() }
            if (host != null) return host

            if (environmentVariable("SMTP_E2E_REQUIRED") != null) {
                fail("SMTP_E2E_REQUIRED is set but SMTP_E2E_HOST is not: the E2E server is missing")
            }

            println("SKIPPED SmtpE2eTest: SMTP_E2E_HOST is not set, start the server with `docker compose up -d`")
            return null
        }
    }
}
