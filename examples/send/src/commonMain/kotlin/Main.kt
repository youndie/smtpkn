import io.github.youndie.smtp.client.Envelope
import io.github.youndie.smtp.client.SmtpClientConfig
import io.github.youndie.smtp.client.openSmtpSession
import io.github.youndie.smtp.mime.MessageBuilder
import io.github.youndie.smtp.protocol.Mailbox
import io.github.youndie.smtp.transport.ktor.connectSmtp
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock

/**
 * Sending one message from a Kotlin/Native service.
 *
 * Against the server in `docker-compose.yml`:
 *
 * ```
 * docker compose up -d
 * ./gradlew :examples:send:runDebugExecutableLinuxX64
 * ```
 *
 * TLS and authentication are left out here on purpose — they need a relay, and the point of this
 * file is the shape of the code. `session.startTls(...)` and `session.authenticate(...)` are the
 * two lines that get added for a real one.
 */
fun main(): Unit =
    runBlocking {
        val transport = connectSmtp(host = "127.0.0.1", port = 1025)
        val session =
            openSmtpSession(
                transport = transport,
                config = SmtpClientConfig(clientIdentity = "example.test"),
            )

        val sender = Mailbox.parse("sender@example.com")
        val recipient = Mailbox.parse("recipient@example.com")

        @Suppress(
            "ktlint:kapkan:wall-clock",
            "`sentAt` — заголовок Date письма: по RFC 5322 это часы отправителя и есть",
        )
        val message =
            MessageBuilder(from = sender, to = listOf(recipient))
                .apply {
                    subject = "Hello from Kotlin/Native"
                    text = "Sent without a JVM anywhere in sight."
                    html = "<p>Sent without a JVM anywhere in sight.</p>"
                }.build(sentAt = Clock.System.now(), messageIdDomain = "example.com")

        val result =
            session.send(
                envelope = Envelope(sender = sender, recipients = listOf(recipient)),
                body = message,
            )

        println("accepted: ${result.accepted}")
        println("rejected: ${result.rejected}")

        session.quit()
    }
