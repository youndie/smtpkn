---
id: feature-authentication
title: Аутентификация на релее
type: feature
status: active
owner: unassigned
involved_services:
  - smtp-client
  - smtp-sasl
  - smtp-core
api:
  - protocol-smtp
tags: [core]
---

# Аутентификация на релее

## 1. Суть

Сабмишн почти всегда требует представиться. Библиотека умеет семь механизмов и выбирает поведение
так, чтобы ошибка в пользу безопасности была поведением по умолчанию.

## 2. Бизнес-ограничения

* **Учётные данные не уходят по открытому каналу** без явного разрешения вызывающего
  (`docs/rfc/rfc8314.txt`).
* **После успешного `AUTH` список расширений недействителен** и запрашивается заново
  (`docs/rfc/rfc4954.txt:297`).
* **`235` от сервера не считается успехом, пока механизм не удовлетворён.** SCRAM аутентифицирует
  и сервер тоже; поверить на слово — обесценить механизм.
* **Слишком длинный начальный ответ — не ошибка**, а повод отправить те же байты обычным шагом
  (`docs/rfc/rfc4954.txt:208`). Токены OAuth задевают этот предел постоянно.

## 3. Якоря кода

| Модуль | Код |
|---|---|
| [smtp-client](../services/smtp-client.md) | `SmtpSession.authenticate` — обмен, base64, отмена |
| [smtp-sasl](../services/smtp-sasl.md) | `Mechanisms.kt` — сами механизмы |

## 4. Сценарии

### Сценарий: вход по PLAIN внутри TLS
* **Дано:** соединение зашифровано, сервер объявил `AUTH PLAIN`.
* **Тогда:** уходит `AUTH PLAIN <base64>`, приходит `235`, клиент повторяет `EHLO`.
* **Автоматизирован:** `SmtpAuthTest.a client-first mechanism sends its response with the command`

### Сценарий: сервер задаёт вопросы (LOGIN, CRAM-MD5, SCRAM)
* **Тогда:** каждый `334` расшифровывается из base64 и отдаётся механизму.
* **Автоматизирован:** `SmtpAuthTest.a server-first mechanism answers challenges`

### Сценарий: неверные учётные данные
* **Тогда:** `SmtpRefusedException` с `isPermanent = true` и расширенным кодом `5.7.8`.
* **Автоматизирован:** `SmtpAuthTest.wrong credentials come back as a permanent refusal`

### Сценарий: механизм отказался продолжать
* **Тогда:** уходит строка из одной `*` (`docs/rfc/rfc4954.txt:194`), затем исключение.
* **Автоматизирован:** `SmtpAuthTest.a mechanism that gives up cancels the exchange`

### Сценарий: попытка отправить пароль открытым текстом
* **Тогда:** исключение до всякой отправки; разрешается только флагом `allowOverPlaintext`.
* **Автоматизирован:** `SmtpAuthTest.credentials are not sent over a cleartext connection`

### Сценарий: вход сразу после `STARTTLS`
* **Дано:** рукопожатие завершилось, `session.isEncrypted` — `true`.
* **Тогда:** `authenticate` идёт без `allowOverPlaintext`: канал защищён, и библиотека это знает
  (`docs/rfc/rfc3207.txt:177`).
* **Автоматизирован:** `SmtpAuthTest.authentication after STARTTLS needs no permission to run over
  cleartext`, а на настоящем провайдере — `StartTlsE2eTest.a message goes out over STARTTLS`

### Сценарий: сервер подделал подпись в SCRAM
* **Тогда:** механизм бросает `SaslException`, сессия не считает вход состоявшимся.
* **Автоматизирован:** `MechanismsTest.SCRAM refuses a wrong server signature`

## 5. Что не входит в скоуп

* **Channel binding (`-PLUS`)** — нужен `tls-exporter` из TLS-слоя, M-56a.
* **Нормализация NFKC в `SaslPrep`** — M-58a.
* **Хранение и обновление токенов OAuth**: библиотека получает готовый токен.
