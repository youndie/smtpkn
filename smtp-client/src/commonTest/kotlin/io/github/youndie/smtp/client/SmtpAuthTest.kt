package io.github.youndie.smtp.client

import io.github.youndie.smtp.protocol.SmtpProtocolException
import io.github.youndie.smtp.protocol.SmtpRefusedException
import io.github.youndie.smtp.sasl.SaslException
import io.github.youndie.smtp.sasl.SaslMechanism
import io.github.youndie.smtp.testing.ScriptedTransport
import io.github.youndie.smtp.testing.scriptedTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `AUTH` exchange, without any particular mechanism.
 *
 * The SMTP profile of SASL — `docs/rfc/rfc4954.txt:699` — is what is under test here: base64,
 * the `=` for an empty client-first message, the `*` that cancels, and the state reset afterwards.
 * The mechanisms themselves are tested in `:smtp-sasl` against the vectors in their own RFCs.
 */
class SmtpAuthTest {
    @Test
    fun `a client-first mechanism sends its response with the command`() =
        runTest {
            // rfc4954.txt:699: auth-command = "AUTH" SP sasl-mech [SP initial-response]
            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE aGVsbG8=\r\n")
                    serverSays("235 2.7.0 Authentication successful")
                    reidentify()
                }

            val session = openAuthenticatedSession(transport, mechanism("hello".encodeToByteArray()))

            assertTrue(session.capabilities.keywords.contains("PIPELINING"))
            transport.assertScriptCompleted()
        }

    @Test
    fun `an empty client-first message is written as an equals sign`() =
        runTest {
            // rfc4954.txt:735: initial-response = base64 / "=". An empty response is not the same
            // as no response at all, and the two are told apart by exactly this character.
            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE =\r\n")
                    serverSays("235 Ok")
                    reidentify()
                }

            openAuthenticatedSession(transport, mechanism(ByteArray(0)))

            transport.assertScriptCompleted()
        }

    @Test
    fun `a server-first mechanism answers challenges`() =
        runTest {
            // rfc4954.txt:721: continue-req = "334" SP [base64] CRLF
            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE\r\n")
                    serverSays("334 VXNlcm5hbWU6")
                    clientWrites("dXNlcg==\r\n")
                    serverSays("334 UGFzc3dvcmQ6")
                    clientWrites("c2VjcmV0\r\n")
                    serverSays("235 Ok")
                    reidentify()
                }

            val answers = mutableListOf<String>()
            val mechanism =
                object : SaslMechanism {
                    override val name = "FAKE"
                    private var step = 0

                    override fun initialResponse(): ByteArray? = null

                    override fun respond(challenge: ByteArray): ByteArray {
                        answers += challenge.decodeToString()
                        step++
                        return if (step == 1) "user".encodeToByteArray() else "secret".encodeToByteArray()
                    }

                    override val isComplete get() = step >= 2
                }

            openAuthenticatedSession(transport, mechanism)

            assertEquals(listOf("Username:", "Password:"), answers)
            transport.assertScriptCompleted()
        }

    @Test
    fun `a challenge with no text decodes to nothing`() =
        runTest {
            // rfc4954.txt:206: "a 334 reply with no text part ... the complete response line
            // is '334 '". A mechanism must see an empty challenge, not a parse error.
            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE\r\n")
                    serverSays("334 ")
                    clientWrites("YQ==\r\n")
                    serverSays("235 Ok")
                    reidentify()
                }

            var seen: Int? = null
            openAuthenticatedSession(
                transport,
                object : SaslMechanism {
                    override val name = "FAKE"

                    override fun initialResponse(): ByteArray? = null

                    override fun respond(challenge: ByteArray): ByteArray {
                        seen = challenge.size
                        return "a".encodeToByteArray()
                    }

                    override val isComplete = true
                },
            )

            assertEquals(0, seen)
        }

    @Test
    fun `an over-long initial response is sent as an ordinary step instead`() =
        runTest {
            // rfc4954.txt:208: "If use of the initial response argument would cause the AUTH
            // command to exceed this length, the client MUST NOT use the initial response
            // parameter". OAuth tokens are long enough for this to be the normal path.
            val token = ByteArray(600) { 'x'.code.toByte() }
            val encoded =
                kotlin.io.encoding.Base64
                    .encode(token)

            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE\r\n")
                    serverSays("334 ")
                    clientWrites("$encoded\r\n")
                    serverSays("235 Ok")
                    reidentify()
                }

            openAuthenticatedSession(transport, mechanism(token))

            transport.assertScriptCompleted()
        }

    @Test
    fun `wrong credentials come back as a permanent refusal`() =
        runTest {
            // rfc4954.txt:600: 535 5.7.8 Authentication credentials invalid.
            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE aGVsbG8=\r\n")
                    serverSays("535 5.7.8 Authentication credentials invalid")
                }

            val failure =
                assertFailsWith<SmtpRefusedException> {
                    openAuthenticatedSession(transport, mechanism("hello".encodeToByteArray()))
                }

            assertTrue(failure.isPermanent)
            assertEquals("5.7.8", failure.reply.enhancedStatus.toString())
        }

    @Test
    fun `a mechanism that gives up cancels the exchange`() =
        runTest {
            // rfc4954.txt:194: the client cancels by sending a line holding a single "*".
            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE\r\n")
                    serverSays("334 bm9uc2Vuc2U=")
                    clientWrites("*\r\n")
                    serverSays("501 5.5.2 Authentication cancelled")
                }

            assertFailsWith<SaslException> {
                openAuthenticatedSession(
                    transport,
                    object : SaslMechanism {
                        override val name = "FAKE"

                        override fun initialResponse(): ByteArray? = null

                        override fun respond(challenge: ByteArray): ByteArray =
                            throw SaslException("FAKE: the server said something unexpected")

                        override val isComplete = false
                    },
                )
            }

            transport.assertScriptCompleted()
        }

    @Test
    fun `success before the mechanism is satisfied is not accepted`() =
        runTest {
            // SCRAM authenticates the server too. A server that says 235 before its proof was
            // checked has proven nothing, and taking its word defeats the mechanism.
            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE aGVsbG8=\r\n")
                    serverSays("235 Ok")
                }

            assertFailsWith<SaslException> {
                openAuthenticatedSession(
                    transport,
                    object : SaslMechanism {
                        override val name = "FAKE"

                        override fun initialResponse() = "hello".encodeToByteArray()

                        override fun respond(challenge: ByteArray) = ByteArray(0)

                        override val isComplete = false
                    },
                )
            }
        }

    @Test
    fun `credentials are not sent over a cleartext connection`() =
        runTest {
            // rfc8314.txt: cleartext submission is obsolete. Sending credentials in the clear is
            // giving them away, so it takes an explicit request.
            val transport =
                scriptedTransport {
                    serverSays("220 smtp.example.com")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 AUTH PLAIN")
                }

            val session = openSession(transport)

            assertFailsWith<SmtpProtocolException> {
                session.authenticate(mechanism("hello".encodeToByteArray()))
            }
        }

    @Test
    fun `authentication over a cleartext connection is possible when asked for in so many words`() =
        runTest {
            val transport =
                scriptedTransport {
                    serverSays("220 smtp.example.com")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 AUTH PLAIN")
                    clientWrites("AUTH FAKE aGVsbG8=\r\n")
                    serverSays("235 Ok")
                    reidentify()
                }

            val session = openSession(transport)
            session.authenticate(mechanism("hello".encodeToByteArray()), allowOverPlaintext = true)

            transport.assertScriptCompleted()
        }

    @Test
    fun `authentication after STARTTLS needs no permission to run over cleartext`() =
        runTest {
            // rfc3207.txt:177: after the handshake both parties MUST decide whether to continue
            // "based on the authentication and privacy achieved" — a session that does not record
            // the handshake cannot make that decision, and refuses the AUTH that rfc4954.txt:326
            // says is exactly what STARTTLS is there to allow.
            val transport =
                scriptedTransport {
                    serverSays("220 smtp.example.com")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 STARTTLS")
                    clientWrites("STARTTLS\r\n")
                    serverSays("220 Ready to start TLS")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 AUTH PLAIN")
                    clientWrites("AUTH FAKE aGVsbG8=\r\n")
                    serverSays("235 Ok")
                    reidentify()
                }

            val session = openSession(transport)
            session.startTls { /* the caller's handshake; here there is no byte layer to swap */ }

            assertTrue(session.isEncrypted, "the session knows the handshake happened")
            session.authenticate(mechanism("hello".encodeToByteArray()))

            transport.assertScriptCompleted()
        }

    @Test
    fun `what the server announced before authentication is discarded`() =
        runTest {
            // rfc4954.txt:297: on success the client MUST discard what it learned earlier — the
            // same reason as after STARTTLS, and the same downgrade if it does not.
            val transport =
                scriptedTransport {
                    encryptedGreeting()
                    clientWrites("AUTH FAKE aGVsbG8=\r\n")
                    serverSays("235 Ok")
                    clientWrites("EHLO client.example.com\r\n")
                    serverSays("250-smtp.example.com", "250 SIZE 100")
                }

            val session = openAuthenticatedSession(transport, mechanism("hello".encodeToByteArray()))
            val stale = sessionCapabilitiesBeforeAuth

            assertEquals(100L, session.capabilities.maxMessageSize)
            assertFailsWith<SmtpProtocolException> { stale!!.supportsPipelining }
        }

    private var sessionCapabilitiesBeforeAuth: io.github.youndie.smtp.protocol.Capabilities? = null

    private suspend fun openAuthenticatedSession(
        transport: ScriptedTransport,
        mechanism: SaslMechanism,
    ): SmtpSession {
        val session = openSession(transport)
        sessionCapabilitiesBeforeAuth = session.capabilities
        session.authenticate(mechanism, allowOverPlaintext = true)
        return session
    }

    private suspend fun openSession(transport: ScriptedTransport) =
        openSmtpSession(
            transport = transport,
            config = SmtpClientConfig(clientIdentity = "client.example.com"),
        )

    /** A mechanism with a client-first message and nothing else to say. */
    private fun mechanism(initial: ByteArray?) =
        object : SaslMechanism {
            override val name = "FAKE"

            override fun initialResponse(): ByteArray? = initial

            override fun respond(challenge: ByteArray): ByteArray = ByteArray(0)

            override val isComplete = true
        }

    private companion object {
        /**
         * The opening every test needs.
         *
         * `allowOverPlaintext` is passed everywhere instead of pretending the scripted transport
         * is encrypted: the tests that matter for the cleartext rule say what they mean, and the
         * one that goes through `STARTTLS` gets there by running it.
         */
        fun ScriptedTransport.Builder.encryptedGreeting() {
            serverSays("220 smtp.example.com")
            clientWrites("EHLO client.example.com\r\n")
            serverSays("250-smtp.example.com", "250-PIPELINING", "250 AUTH PLAIN LOGIN")
        }

        /** The second `EHLO`, which the client owes the server after a successful `AUTH`. */
        fun ScriptedTransport.Builder.reidentify() {
            clientWrites("EHLO client.example.com\r\n")
            serverSays("250-smtp.example.com", "250 PIPELINING")
        }
    }
}
