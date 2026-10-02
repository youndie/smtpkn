# Документация smtpkn

Слои, связанные ссылками сверху вниз:

```
[ Research — почему архитектура именно такая, что проверено, что гипотеза ]
                              │
[ Feature — что делает библиотека и зачем + BDD-сценарии = критерии приёмки ]
                              │
[ Protocol — контракт на проводе: команды, ответы, расширения ]
                              │
[ Module — зона ответственности модуля, зависимости, сборка, грабли ]
```

| Слой | Папка | Отвечает на вопрос | Источник правды |
|---|---|---|---|
| Research | `research/` | Почему выбрано так, что проверено, чем платим | артефакты зависимостей и тексты RFC |
| Feature | `features/` | Что библиотека умеет и как это принимается | этот репозиторий + тесты |
| Protocol | `api/` | Что уходит и приходит по сокету | `rfc/` |
| Module | `services/` | Что за модуль, от чего зависит, как собирается | код модуля |

Слой экранов не заводится: клиента у библиотеки нет.

## Документы

- [research/research-architecture.md](research/research-architecture.md) — **точка входа**.
  Проверенные факты о Ktor и Kotlin/Native, принятые решения, риски, открытые вопросы.
- [api/protocol-smtp.md](api/protocol-smtp.md) — контракт SMTP на проводе с ссылками на строки RFC.
  По нему пишутся тесты.
- [features/feature-send-message.md](features/feature-send-message.md) — что библиотека даёт
  пользователю, со сценариями приёмки.
- [services/smtp-core.md](services/smtp-core.md) — модуль ядра: зона ответственности, якоря кода,
  грабли.
- [services/smtp-client.md](services/smtp-client.md) — сессия, транзакции, таймауты.
- [services/smtp-testing.md](services/smtp-testing.md) — сценарный транспорт для тестов.
- [services/smtp-tls-openssl.md](services/smtp-tls-openssl.md) — TLS: memory BIO, проверка
  сертификата, грабли cinterop.
- [services/smtp-sasl.md](services/smtp-sasl.md) — механизмы SASL и их векторы.
- [services/smtp-tls-jvm.md](services/smtp-tls-jvm.md) — TLS на JVM.
- [services/smtp-mime.md](services/smtp-mime.md) — построение письма.
- [services/smtp-transport-ktor.md](services/smtp-transport-ktor.md) — TCP-транспорт и как гонять
  E2E против сервера из `docker-compose.yml`.
- [rfc/README.md](rfc/README.md) — 42 копии RFC в репозитории, разложенные по вехам.
- [../BACKLOG.md](../BACKLOG.md) — вехи M0…M10 и задачи `M-NN`.
- [../RELEASING.md](../RELEASING.md) — процедура выпуска и почему артефакты собираются с двух машин.

## Карта покрытия

### Research (1/1)
- [x] [research-architecture](research/research-architecture.md)

### Protocol (1/1)
- [x] [protocol-smtp](api/protocol-smtp.md) — разделы 1–2, 4–5, 8 реализованы в `smtp-core`

### Features (2)
- [x] [feature-send-message](features/feature-send-message.md) — отправка через сабмишн-релей
- [ ] `feature-tls` — M4
- [x] [feature-authentication](features/feature-authentication.md) — семь механизмов SASL

### Modules (8/8)
- [x] [smtp-core](services/smtp-core.md) — протокол и домен, без I/O
- [x] [smtp-client](services/smtp-client.md) — сессия и транзакции
- [x] [smtp-transport-ktor](services/smtp-transport-ktor.md) — TCP поверх ktor-network
- [x] [smtp-testing](services/smtp-testing.md) — сценарный транспорт
- [x] [smtp-tls-openssl](services/smtp-tls-openssl.md) — TLS через OpenSSL, только нативные таргеты
- [x] [smtp-tls-jvm](services/smtp-tls-jvm.md) — TLS через SSLEngine
- [x] [smtp-sasl](services/smtp-sasl.md) — механизмы аутентификации
- [x] [smtp-mime](services/smtp-mime.md) — построение письма

## Соглашения

- **`id` во frontmatter равен имени файла.** Ссылки между слоями — по id во frontmatter и обычными
  markdown-ссылками в тексте.
- **`main` описывает то, что есть.** Замысел живёт в открытом PR (`status: draft`) или помечен
  словом «целевое»/«гипотеза» прямо в тексте.
- **Проверенное отделяется от предполагаемого** явно, с колонкой «где проверено».
- **Ссылка на RFC — это ссылка на строку в `rfc/`**: `rfc5321.txt:3510`. Не «см. RFC 5321».
- **Якоря кода вместо пересказа кода.** Абзац, который можно заменить путём к файлу, заменяется
  путём к файлу.
- **Секция Quirks не удаляется** при правке документа — только после проверки, что поведение
  действительно изменилось.
- **Язык документации — русский, язык кода — английский.** Комментарии, KDoc, имена тестов,
  сообщения исключений и сообщения коммитов пишутся по-английски: библиотека открытая.
  Корневой `README.md` — тоже английский, это витрина проекта. Подробнее — в
  [CONTRIBUTING.md](../CONTRIBUTING.md#язык).
- Идентификаторы, команды SMTP и ключевые слова расширений — вербатим, как на проводе
  (`MAIL FROM`, `STARTTLS`, `8BITMIME`), в любом тексте.

## Шаблоны

`templates/feature.md`, `templates/service.md`, `templates/endpoint.md`,
`templates/research-architecture.md` — копируются и заполняются. Секции с пометкой
`<!-- optional -->` можно удалять.
