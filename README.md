# smtpkn

[![ktlint](https://img.shields.io/badge/ktlint%20code--style-%E2%9D%A4-FF4081.svg)](https://ktlint.github.io/)
[![kotlin](https://img.shields.io/badge/Kotlin-2.4.20-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![platform](https://img.shields.io/badge/platform-linuxX64-blue?logo=linux&logoColor=white)](#platform-support)
[![smtp-client](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/smtp-client?name=smtp-client&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/smtp-client)
[![license](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

SMTP for Kotlin Multiplatform: RFC 5321 written from the specification, built so that a service on
Kotlin/Native can send mail without a JVM anywhere in sight.

On the JVM this was solved long ago — Jakarta Mail, Simple Java Mail. On Kotlin/Native there is
nothing: the options are shelling out to `sendmail` or wrapping libcurl, and both hide the protocol
exactly where it needs to be visible. This library implements it instead, over sockets, with
`STARTTLS`, SASL and the ESMTP extensions.

## Overview

- Submission per RFC 6409: port 587 with `STARTTLS`, port 465 with implicit TLS
- Reply parsing that streams: multiline replies, enhanced status codes, replies matched to commands
  **by counting**, because matching them by code or text is expressly forbidden
- Sessions with phases: `EHLO` with a `HELO` fallback, transactions, `RSET`, timeouts whose
  defaults are the RFC minimums
- A partial refusal is a **result**, not an exception — losing who did receive the message is the
  difference between a retry and a duplicate
- TLS with the certificate chain and the host name actually verified, through OpenSSL on
  Kotlin/Native and `SSLEngine` on the JVM
- Seven SASL mechanisms: `PLAIN`, `LOGIN`, `CRAM-MD5`, `SCRAM-SHA-1`, `SCRAM-SHA-256`, `XOAUTH2`,
  `OAUTHBEARER`
- ESMTP extensions: `PIPELINING`, `SIZE`, `8BITMIME`, `SMTPUTF8`, `DSN`, `ENHANCEDSTATUSCODES`,
  `CHUNKING`, plus punycode for internationalised domains
- Message building: RFC 5322 headers, `multipart/alternative`, attachments, encoded words
- Anything a rule forbids is refused rather than worked around: a line break inside an address, a
  subject that would add headers of somebody else's choosing, credentials over a cleartext channel

## Platform support

**`linuxX64` is the platform this is built for and the only one it is claimed to work on.** It is
where every milestone is closed: the whole test suite runs there, including TLS against a real
server and end to end against Mailpit and Postfix.

| | |
|---|---|
| `linuxX64` | supported — full suite, TLS and end to end in CI |
| `jvm` | everything but TLS-through-`SSLEngine` shares the same code and runs in CI; not claimed yet only because nothing has been released |
| `macosArm64` | the development host: unit tests run in CI, TLS works, no end to end |
| `linuxArm64`, `macosX64`, `mingwX64`, `iosArm64` | compile in every build; no test has ever been executed on them, and there is no TLS provider for Windows or Apple |
| `js`, `wasmJs` | `smtp-core`, `smtp-sasl` and `smtp-mime` only — parsing and building a message. There is no TCP socket in a browser and no Node transport yet, so nothing can be sent from there |

Compiling is not the same as working, and this table draws the line where it actually is: a target
whose tests have never run says nothing about whether the code behaves there.

## Add dependencies

```kotlin
repositories {
    mavenCentral()
    maven {
        name = "WipSnapshots"
        url = uri("https://reposilite.kotlin.website/snapshots")
    }
}

dependencies {
    implementation("io.github.youndie:smtp-client:0.1.0-SNAPSHOT")
    implementation("io.github.youndie:smtp-transport-ktor:0.1.0-SNAPSHOT")
    implementation("io.github.youndie:smtp-tls-openssl:0.1.0-SNAPSHOT") // TLS on Kotlin/Native
    implementation("io.github.youndie:smtp-tls-jvm:0.1.0-SNAPSHOT")     // TLS on the JVM
    implementation("io.github.youndie:smtp-sasl:0.1.0-SNAPSHOT")        // authentication
    implementation("io.github.youndie:smtp-mime:0.1.0-SNAPSHOT")        // building messages
}
```

OpenSSL 3 must be installed for the native TLS module: `apt install libssl-dev` on Linux,
`brew install openssl@3` on macOS. That module builds for the host target only — `cinterop` needs
the headers of the target platform — so its artefacts are published from two machines.

Snapshots only so far. Maven Central is configured but nothing has been released; see
[RELEASING.md](RELEASING.md).

## Usage

Submission through a relay: port 587, `STARTTLS`, `AUTH`.

```kotlin
fun main() = runBlocking {
    val transport = connectSmtp("smtp.example.com", 587)
    val session = openSmtpSession(
        transport = transport,
        config = SmtpClientConfig(clientIdentity = "my-service.example.com"),
    )

    session.startTls(OpenSslTlsProvider, TlsConfig(serverName = "smtp.example.com"))
    session.authenticate(PlainMechanism(username = "user", password = "secret"))

    val sender = Mailbox.parse("noreply@example.com")
    val recipient = Mailbox.parse("user@example.com")

    val message = MessageBuilder(from = sender, to = listOf(recipient)).apply {
        subject = "Hello"
        text = "Sent without a JVM anywhere in sight."
        html = "<p>Sent without a JVM anywhere in sight.</p>"
    }.build(sentAt = Clock.System.now(), messageIdDomain = "example.com")

    val result = session.send(
        envelope = Envelope(sender = sender, recipients = listOf(recipient)),
        body = message,
    )

    println(result.accepted)  // who got it
    println(result.rejected)  // who did not, and with which code
    session.quit()
}
```

A runnable version is [examples/send](examples/send/src/commonMain/kotlin/Main.kt); it is compiled
by every build, so it cannot rot unnoticed.

`SendOptions` carries the ESMTP parameters — `SIZE`, `BODY=8BITMIME`, `SMTPUTF8`, `DSN`, pipelining,
chunking. A parameter is only sent when the server announced the extension, and asking for one it
never offered is an error rather than a silent downgrade.

## Internals

| Layer | |
|---|---|
| Protocol | pure functions over strings and bytes: replies, commands, addresses, transparency. No I/O and no third-party types, so almost every test runs without a socket |
| Ports | `ByteConnection` under `SmtpTransport` under the session. TLS is inserted between the first two, which is exactly the shape `STARTTLS` needs |
| Sockets | `ktor-network`, and nothing else from Ktor — `ktor-network-tls` is a stub on Kotlin/Native that throws at runtime |
| TLS | OpenSSL over memory BIOs on Native, `SSLEngine` on the JVM. Neither is handed a file descriptor: a Ktor socket does not expose one |
| Limits | every length is measured in **octets**, not characters — with SMTPUTF8 the difference is twofold |

| Module | |
|---|---|
| `smtp-core` | protocol, domain, ports. Depends on the standard library and nothing else |
| `smtp-client` | sessions, transactions, timeouts, `AUTH` |
| `smtp-transport-ktor` | TCP; the only place that mentions Ktor |
| `smtp-tls-openssl` | TLS on Kotlin/Native through cinterop |
| `smtp-tls-jvm` | TLS on the JVM through `SSLEngine` |
| `smtp-sasl` | the seven mechanisms |
| `smtp-mime` | building messages |
| `smtp-testing` | a scripted transport and a fake SMTP server |

## Testing

181 tests on `linuxX64`, 175 on the JVM. Each cites the line of the RFC it came from, because SMTP
is full of places where common sense and the specification disagree.

The mechanisms are checked against the **vectors printed in their own RFCs**: the CRAM-MD5 digest of
RFC 2195, and the full SCRAM exchanges of RFC 5802 and RFC 7677, server signature included. A vector
out of the specification catches an implementation that is wrong in the same way twice, which a
self-consistent test never does.

TLS is verified by two tests that must **fail**: an unknown certificate authority, and a valid
certificate issued for another name. Until those go red when verification breaks, TLS is not
considered checked — encrypting without proving anything looks exactly like working.

End to end runs against two servers, because they disagree. Mailpit answers over HTTP, so the test
asks what was actually stored instead of trusting a `250`; Postfix is strict, and refused
`example.com` on the first attempt because that domain publishes a null MX. The body carries a line
holding a single period — with dot-stuffing wrong it either truncates or arrives doubled, and both
look like success on the wire.

One connection carries 1000 messages and is still a working session afterwards, at roughly
1800 messages/s against an in-process server. That number measures the client, not a network.

## What this is not

- **Not a mail server, and not direct-to-MX delivery.** Sending straight to a recipient's MX needs a
  DNS resolver that can ask for MX records, and Kotlin/Native has only `getaddrinfo`.
- **Not a complete MIME stack.** Headers, `multipart/alternative`, attachments — yes. S/MIME, deeply
  nested parts and RFC 2231 parameters — no.
- **Not usable in a browser.** There is no TCP socket there, so a browser artefact could not work
  even in principle; `js` and `wasmJs` are published only for the modules that do not need one.
- **Not SASLprep-complete.** Mapping and prohibition are applied, Unicode normalisation is not:
  Kotlin has no normalisation in common code. ASCII credentials are unaffected.

## Documentation

[docs/](docs/) — the architecture research with the reasoning behind each decision, the wire
contract every test is written from, and 42 RFCs kept in the repository so that a citation can be
opened offline. Written in Russian.

Read the research before changing anything: several decisions here are counter-intuitive, and the
backlog records what was measured and turned out otherwise — starting with `Socket.tls()` on
Kotlin/Native, which compiles and then throws.

## License

MIT. See [LICENSE](LICENSE).
