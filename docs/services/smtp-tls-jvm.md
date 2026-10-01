---
id: smtp-tls-jvm
title: smtp-tls-jvm — TLS через SSLEngine
type: service
status: active
module: :smtp-tls-jvm
tech_stack: [Kotlin/JVM]
targets: [jvm]
owner: unassigned
depends_on:
  - smtp-core
publishes:
  - io.github.youndie:smtp-tls-jvm
---

# smtp-tls-jvm

## 1. Зона ответственности

Тот же порт `TlsProvider`, что и у [smtp-tls-openssl](smtp-tls-openssl.md), но на JVM и через
`SSLEngine`.

## 2. Как устроено

`SSLEngine` — это ответ JVM на ту же задачу, которую OpenSSL решает memory BIO: он преобразует
буферы, а перекладывание байтов оставляет вызывающему. Поэтому он лёг в существующий порт без
единой уступки в интерфейсе — сокета здесь тоже нет.

Проверка имени включается `endpointIdentificationAlgorithm = "HTTPS"`: `rfc7817.txt` отсылает
к тому же алгоритму и для почты, а выставленный на движке он роняет рукопожатие вместо того, чтобы
превратиться в результат, который забывают прочитать.

## 3. Якоря кода

| Файл | Что там |
|---|---|
| `src/main/kotlin/.../SslEngineTlsProvider.kt` | контекст, доверенный список, обёртка соединения |
| `src/test/kotlin/.../SslEngineTlsTest.kt` | те же три вопроса, что и к OpenSSL |

## 4. Сознательные ограничения / грабли

* **Модуль не мультиплатформенный.** Здесь платформенный API и есть весь смысл; KMP-обёртка
  добавила бы слой, за которым с другой стороны ничего нет.
* **`sourceCompatibility` задаётся явно.** Иначе Gradle падает на несовпадении цели Java (25 из
  тулчейна) и Kotlin (17).
