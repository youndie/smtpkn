package io.github.youndie.smtp.testing

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One message as the fake server received it, with transparency already undone. */
public class ReceivedMessage(
    public val sender: String,
    public val recipients: List<String>,
    public val body: List<String>,
)

/**
 * An SMTP server good enough to answer a real client, and strict enough to catch it misbehaving.
 *
 * It exists because the container in `docker-compose.yml` is tolerant: Mailpit accepts almost
 * anything, so it cannot fail a client that breaks the rules. This one can — [strict] turns on the
 * length checks the specification states — and it needs no Docker, so it runs wherever the tests do.
 *
 * Not a mail server: nothing is delivered, stored or forwarded. Messages land in [received].
 */
public class FakeSmtpServer(
    private val extensions: List<String> = listOf("PIPELINING", "8BITMIME"),
    private val rejectRecipients: Set<String> = emptySet(),
    private val strict: Boolean = false,
    private val greeting: String = "fake.smtp.test",
) {
    private val selector = SelectorManager(Dispatchers.Default)
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val messages = mutableListOf<ReceivedMessage>()

    private var socket: ServerSocket? = null

    /** The port the server is listening on. Allocated by the operating system, so tests never clash. */
    public var port: Int = 0
        private set

    public val received: List<ReceivedMessage> get() = messages.toList()

    public suspend fun start() {
        val bound = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
        socket = bound
        port = (bound.localAddress as InetSocketAddress).port

        scope.launch {
            while (isActive) {
                @Suppress(
                    "ktlint:kapkan:swallowed-failure",
                    "сокет закрыт при остановке сервера — выход из цикла приёма и есть ответ",
                )
                val accepted =
                    try {
                        bound.accept()
                    } catch (_: Throwable) {
                        return@launch
                    }
                launch { serve(accepted.openReadChannel(), accepted.openWriteChannel(autoFlush = true)) }
            }
        }
    }

    public suspend fun stop() {
        socket?.close()
        selector.close()
        scope.cancel()
    }

    private suspend fun serve(
        input: ByteReadChannel,
        output: ByteWriteChannel,
    ) {
        output.say("220 $greeting ESMTP fake")

        var sender: String? = null
        val recipients = mutableListOf<String>()

        while (true) {
            val line = input.readLine() ?: return

            if (strict && line.encodeToByteArray().size > MAX_COMMAND_OCTETS) {
                // rfc5321.txt:3498, and rfc5321.txt:3555 names 500 as the reply for it.
                output.say("500 5.5.2 Line too long")
                continue
            }

            val verb = line.substringBefore(' ').uppercase()
            when {
                verb == "EHLO" -> {
                    val lines = listOf(greeting) + extensions
                    lines.forEachIndexed { index, text ->
                        val separator = if (index == lines.lastIndex) " " else "-"
                        output.say("250$separator$text")
                    }
                }

                verb == "HELO" -> {
                    output.say("250 $greeting")
                }

                line.startsWith("MAIL FROM:", ignoreCase = true) -> {
                    sender =
                        line
                            .substringAfter(':')
                            .trim()
                            .substringBefore(' ')
                            .trim('<', '>')
                    recipients.clear()
                    output.say("250 2.1.0 Ok")
                }

                line.startsWith("RCPT TO:", ignoreCase = true) -> {
                    val recipient =
                        line
                            .substringAfter(':')
                            .trim()
                            .substringBefore(' ')
                            .trim('<', '>')
                    if (recipient in rejectRecipients) {
                        output.say("550 5.1.1 <$recipient>: Recipient address rejected")
                    } else {
                        recipients += recipient
                        output.say("250 2.1.5 Ok")
                    }
                }

                verb == "DATA" -> {
                    output.say("354 End data with <CRLF>.<CRLF>")
                    val body = readBody(input, output) ?: return
                    messages += ReceivedMessage(sender.orEmpty(), recipients.toList(), body)
                    recipients.clear()
                    output.say("250 2.0.0 Ok: queued as FAKE")
                }

                verb == "RSET" -> {
                    sender = null
                    recipients.clear()
                    output.say("250 2.0.0 Ok")
                }

                verb == "NOOP" -> {
                    output.say("250 2.0.0 Ok")
                }

                verb == "QUIT" -> {
                    output.say("221 2.0.0 Bye")
                    return
                }

                else -> {
                    output.say("500 5.5.2 Command not recognized")
                }
            }
        }
    }

    /** Reads until the lone period, undoing transparency — `docs/rfc/rfc5321.txt:3423`. */
    private suspend fun readBody(
        input: ByteReadChannel,
        output: ByteWriteChannel,
    ): List<String>? {
        val body = mutableListOf<String>()
        while (true) {
            val line = input.readLine() ?: return null
            if (line == ".") return body

            if (strict && line.encodeToByteArray().size > MAX_TEXT_OCTETS) {
                // rfc5321.txt:3510
                output.say("500 5.5.2 Line too long")
                return null
            }

            body += if (line.startsWith("..")) line.drop(1) else line
        }
    }

    private suspend fun ByteWriteChannel.say(line: String) {
        writeString(line + "\r\n")
        flush()
    }

    private companion object {
        /** `docs/rfc/rfc5321.txt:3498`: 512 octets including CRLF. */
        const val MAX_COMMAND_OCTETS = 510

        /** `docs/rfc/rfc5321.txt:3510`: 1000 octets including CRLF. */
        const val MAX_TEXT_OCTETS = 998
    }
}
