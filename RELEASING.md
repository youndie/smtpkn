# Релиз

## Снапшоты

Снапшоты уезжают в приватный Reposilite (`https://reposilite.kotlin.website/snapshots`) — кнопкой
**Publish snapshot** в Actions. Креды берутся из секретов репозитория `REPOSILITE_USER` и
`REPOSILITE_SECRET`, подпись не нужна: её требует Central, а снапшот-репозиторий нет.

Задание разложено на два: macOS публикует всё, включая кросс-скомпилированные klib для Linux,
Windows и Apple, а Linux — только `linuxX64`-половину `:smtp-tls-openssl`, потому что cinterop
требует заголовков целевой платформы и собрать её больше негде.

Подключение снапшота у потребителя:

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots")
}
```

## Пригодность к линковке

Зелёная сборка этого репозитория не доказывает, что опубликованный `:smtp-tls-openssl` слинкуется
у потребителя: `examples/send` делит с модулем source set и опции линковки и такой дефект не видит
по построению (M-110). Доказывает отдельная сборка `tools/consumer-check` — она знает только
координату и URL репозитория и не несёт ни одной своей опции линковки. В CI она гоняется против
артефакта, опубликованного в файловый репозиторий `build/consumer-check-repo`; против снапшота
в Reposilite — руками:

```bash
./gradlew -p tools/consumer-check consumerCheck
```

Версия и репозиторий задаются `-PsmtpknVersion=…` и `-PsmtpknRepo=…`; по умолчанию —
`0.1.0-SNAPSHOT` из Reposilite. Проверяется хостовый таргет: на Linux `linuxX64`, на macOS
`macosArm64`.

## Релиз

Публикация идёт в Maven Central через Central Portal. Артефакты подписываются; ключ и пароли
берутся из окружения, поэтому клон репозитория собирается и тестируется, но выпустить релиз
не может.

## Что нужно один раз

| Переменная | Что это |
|---|---|
| `ORG_GRADLE_PROJECT_mavenCentralUsername` | токен Central Portal |
| `ORG_GRADLE_PROJECT_mavenCentralPassword` | пароль токена |
| `ORG_GRADLE_PROJECT_signingInMemoryKey` | секретный ключ GPG в ASCII-armor |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` | пароль ключа |

Namespace `io.github.youndie` проверяется через GitHub-аккаунт — отдельная верификация домена
не нужна.

## Порядок

Модуль `:smtp-tls-openssl` собирается **только под хостовый таргет**: cinterop требует заголовков
целевой платформы. Поэтому нативные артефакты выпускаются с двух машин, и это не неудобство,
а следствие решения D2.

```bash
./gradlew publishToMavenLocal
```

Проверить, что в `~/.m2/repository/io/github/youndie/` лежит то, что ожидается.

С Linux (даёт `linuxX64`, `linuxArm64`, `jvm`, `js`, `wasmJs` и TLS для Linux):

```bash
./gradlew publishAllPublicationsToMavenCentralRepository
```

С macOS (даёт `macosX64`, `macosArm64`, `mingwX64`, `iosArm64` и TLS для macOS):

```bash
./gradlew publishAllPublicationsToMavenCentralRepository
```

Затем в интерфейсе Central Portal проверить состав bundle и нажать release. Автоматический релиз
намеренно выключен: отозвать выпущенную версию нельзя.

## Чего не делать

- Не выпускать `SNAPSHOT` как релиз: версия задаётся `-Pversion=1.2.3`.
- Не публиковать `:examples:send` — он не библиотека и плагина публикации не имеет.
- Не менять `group` после первого релиза: координаты в Central неизменны.
