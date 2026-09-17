---
id: smtp-tls-openssl
title: smtp-tls-openssl — TLS через OpenSSL
type: service
status: active
module: :smtp-tls-openssl
tech_stack: [Kotlin/Native, cinterop, OpenSSL 3]
targets: [linuxX64, macosArm64]
owner: unassigned
depends_on:
  - smtp-core
publishes:
  - io.github.youndie:smtp-tls-openssl
---

# smtp-tls-openssl

## 1. Зона ответственности

Реализация порта `TlsProvider`: берёт `ByteConnection`, возвращает `ByteConnection`, поверх
которого уже TLS. Проверка цепочки сертификатов и сверка имени хоста — часть работы, а не
опция сверху.

**Чем не занимается:** сокетами и протоколом SMTP. Про `STARTTLS` знает только то, что его
рукопожатие происходит поверх уже открытого соединения.

## 2. Контракт

`TlsProvider` и `TlsConfig` из [smtp-core](smtp-core.md),
`src/commonMain/kotlin/io/github/youndie/smtp/transport/TlsProvider.kt`.

## 2а. Ключевые файлы (якоря кода)

| Файл | Что там |
|---|---|
| `src/nativeInterop/cinterop/openssl.def` | заголовки + C-обёртки над макросами OpenSSL |
| `src/nativeMain/.../OpenSslTlsProvider.kt` | рукопожатие, проверка, перекачка байтов |
| `src/nativeTest/.../OpenSslTlsTest.kt` | пять тестов против настоящего сервера |
| `build.gradle.kts` | поиск OpenSSL, includeDirs, генерация `.def` с `linkerOpts` |
| `../tools/consumer-check/` | сборка-потребитель: линкует и запускает бинарь против опубликованного klib |
| `../tools/generate-test-certs.sh` | CA и сертификат для тестов |

## 3. Как устроено

Дескриптор сокета OpenSSL не отдаётся — его просто неоткуда взять
([research 1.1](../research/research-architecture.md)). Вместо этого две memory BIO: одна
принимает шифротекст из сокета, вторая отдаёт то, что OpenSSL хочет отправить. Байты между ними
и транспортом перекачиваются руками. Дороже на пару сотен строк, зато `STARTTLS` — обычная
подмена слоя, и тот же код работает поверх любого транспорта.

Макросы OpenSSL (`SSL_set_tlsext_host_name`, `SSL_CTX_set_min_proto_version`, коды
`SSL_ERROR_*`) cinterop не видит, поэтому в `.def` для них написаны маленькие C-функции.

Минимальная версия протокола — TLS 1.2 (`docs/rfc/rfc8314.txt`). Имя сверяется через
`SSL_set1_host`, то есть несовпадение роняет рукопожатие, а не всплывает потом отдельным флагом,
который легко забыть проверить.

## 4. Зависимости

| Тип | Имя | Для чего |
|---|---|---|
| Модуль | [smtp-core](smtp-core.md) | порты `ByteConnection`, `TlsProvider` |
| Системная | OpenSSL 3 (`libssl-dev` / `brew install openssl@3`) | сам TLS |
| Модуль (тесты) | [smtp-transport-ktor](smtp-transport-ktor.md) | сокет под тестами |

## 5. Тесты

```bash
tools/generate-test-certs.sh
docker compose up -d
SMTP_TLS_E2E_HOST=127.0.0.1 SMTP_TLS_E2E_CA="$PWD/build/e2e-certs/ca.pem" \
  ./gradlew :smtp-tls-openssl:macosArm64Test
```

Два теста из пяти обязаны **падать**: сертификат от неизвестного удостоверяющего центра и
сертификат, выписанный на другое имя. Пока они не красные при поломке проверки, TLS считается
непроверенным.

## 6. Сознательные ограничения / грабли

* **Только хостовый таргет.** cinterop требует заголовков **целевой** платформы, поэтому собрать
  этот модуль под linuxX64 с macOS нельзя вовсе. Следствие для релиза: артефакты linuxX64
  публикуются с Linux-раннера (M-100).
* **Debian кладёт `opensslconf.h` в `include/<triplet>/openssl`**, а не рядом с `ssl.h`. Указать
  cinterop только на префикс — получить «'openssl/opensslconf.h' file not found».
* **Опции линковки обязаны ехать в klib, а не только к своим бинарям.** `-L…`, `-lssl`, `-lcrypto`
  и `--allow-shlib-undefined` стояли в `binaries.all`: свои тесты линковались, а потребитель получал
  `undefined symbol: OpenSSL_version_num` (M-110). Поэтому `build.gradle.kts` генерирует
  `build/cinterop/openssl.def` — исходный текст плюс `linkerOpts` и `libraryPaths` с уже
  разрешёнными путями — и cinterop читает его, а не файл из `src/`. Каталог поиска нужен **внутри**
  `linkerOpts`: `libraryPaths` действует на этапе cinterop и до линковки у потребителя не доходит.
  Приёмка — `tools/consumer-check`: отдельная сборка без единой своей опции линковки, в CI она
  линкует и запускает бинарь против артефакта, только что опубликованного в файловый репозиторий.
* **`--allow-shlib-undefined` на Linux, без префикса `-Wl,`**: sysroot Kotlin/Native намеренно со
  старой glibc, а системная `libssl` ссылается на символы новее (`stat@GLIBC_2.33` и прочие);
  разрешает их динамический загрузчик при запуске. Из `.def` опции уходят прямо в `ld.lld`, а не
  через драйвер компилятора, так что драйверная запись `-Wl,…` там не принимается (измерено
  в mongkn, чья починка здесь повторена).
* **Владение передаётся ровно один раз.** До создания `OpenSslConnection` всё освобождает
  фабрика, после — только сама связь. Ошибка здесь не утечка, а двойное освобождение: процесс
  падает вместо провала теста, и в отчёте это выглядит как «часть тестов не запускалась».
* **`SSL_get_verify_result` возвращает ошибку даже при `SSL_VERIFY_NONE`.** Проверять его
  безусловно — значит сделать явное отключение проверки неработающим.
* **musl не поддерживается**: Kotlin/Native собран под glibc, на alpine не работают ни компилятор,
  ни бинарники (KT-38891, KT-38876). Между версиями glibc бинарник переносится: собранный на
  Ubuntu 24.04 (glibc 2.39) прогнан на Ubuntu 22.04 (glibc 2.35), все тесты зелёные.
* **Очередь ошибок OpenSSL чистится перед каждой операцией** (`ERR_clear_error`). Она потоковая и
  переживает вызовы, а `SSL_get_error` при непустой очереди возвращает неправду: успешное чтение
  выглядит как отказ сертификата, причём с текстом ошибки от совсем другого соединения. Именно так
  и проявился «разовый необъяснимый провал» STARTTLS — он зависел от того, какой тест отработал
  перед ним.
