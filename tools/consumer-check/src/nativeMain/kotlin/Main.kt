import io.github.youndie.smtp.tls.openssl.OpenSslTlsProvider

/**
 * Touches one OpenSSL symbol through the published library and nothing else.
 *
 * `libraryVersion` reads `OpenSSL_version_num` from libcrypto: if this binary links, the klib
 * carried its own linker options; if it starts, the shared object was found at run time as well.
 */
fun main() {
    println("smtp-tls-openssl links: OpenSSL ${OpenSslTlsProvider.libraryVersion}")
}
