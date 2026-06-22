<h1 align="center">
  <img src="assets/logo.jpg" alt="Point & Point logo" width="32" height="32"/>
  Point &amp; Point - P2P Messenger
</h1>


![Release](https://img.shields.io/github/actions/workflow/status/Triddov/p2p/release.yaml?branch=main&label=release&logo=github)
![Deploy](https://img.shields.io/github/actions/workflow/status/Triddov/p2p/deploy.yaml?branch=main&label=deploy&logo=github)
![Go](https://img.shields.io/badge/Go-1.25-00ADD8?logo=go&logoColor=white)
![Android](https://img.shields.io/badge/Android-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Docker](https://img.shields.io/docker/image-size/triddov/p2p-api/latest?label=api%20image&logo=docker)
![Docker](https://img.shields.io/docker/image-size/triddov/p2p-signaling/latest?label=signaling%20image&logo=docker)
![License](https://img.shields.io/badge/license-MIT-blue)


Гибридный P2P-мессенджер с end-to-end шифрованием. Сообщения передаются напрямую между устройствами через WebRTC DataChannel; если получатель офлайн - сообщение хранится на сервере в зашифрованном виде до доставки. Сервер не имеет доступа к ключам шифрования.


## Architecture


```
┌─────────────────────────────────────────────────────-────┐
│                   Android Client (Kotlin)                │
│  Jetpack Compose · MVVM · Hilt · Room · libsignal        │
└───────────┬──────────────────────────┬───────────────────┘
            │ REST (Retrofit)          │ WebSocket (OkHttp)
            │ Bearer JWT               │ ?token=<jwt>
            ▼                          ▼
┌───────────────────┐      ┌─────────────────────┐
│   REST API        │      │  Signaling Server   │
│   Go / Gin        │      │  Go / Gin           │
│  auth·users·keys  │      │  WebSocket hub      │
│  ·messages        │      │  (offer/answer/ICE) │
└────────┬──────────┘      └──────────┬──────────┘
         │                            │
         ▼                            ▼
┌─────────────────────────────────────────────────────────┐
│                     PostgreSQL                          │
└─────────────────────────────────────────────────────────┘
         ▲
┌────────┴──────────┐      ┌─────────────────────┐
│   Redis           │      │  coturn (TURN)      │
│   rate limiting   │      │  UDP/TCP relay      │
│   OTP · refresh   │      │  (NAT traversal)    │
└───────────────────┘      └─────────────────────┘
```

Два независимых Go-сервиса (REST API и Signaling) разделяют одну PostgreSQL.
Клиент общается с REST по HTTPS (Retrofit + Bearer JWT) и держит WebSocket к
Signaling для P2P-переговоров. Сами сообщения идут **напрямую между устройствами**
(WebRTC DataChannel), а сервер - лишь резервный канал для офлайн-доставки и
точка координации; расшифровать сообщения он не может.

---

### 1. Регистрация и вход (OTP + генерация ключей)

Пароля нет. Пользователь вводит email, получает 6-значный код,
подтверждает его. При самом первом запуске клиент локально генерирует весь набор
ключей Signal Protocol (identity-ключ, registration ID, signed prekey и пул из 100
одноразовых prekey), отправляет на сервер публичную часть и получает пару токенов
(access + refresh). После этого обязателен шаг выбора уникального `username`.


- Запрос кода: `POST /api/auth/request-code` -> `internal/auth/handler.go` ->
  `service.RequestVerificationCode` (генерация кода, rate-limit и TTL в Redis,
  письмо через `pkg/email`).
- Подтверждение: `POST /api/auth/verify` -> `auth.VerifyCodeAndRegister`
  (создаёт пользователя, сохраняет `identity_public_key`, выдаёт access-JWT и
  refresh-токен). JWT - `pkg/jwt/token.go`.
- Клиент: `ui/auth/AuthViewModel.kt` -> `data/repository/AuthRepository.kt`
  (`verifyCodeAndRegister`). Генерация ключей - `domain/crypto/CryptoManager.kt`
  (`initializeKeys`), затем загрузка пакета: `PUT /api/keys/prekeys`.
- Экран ника (непропускаемый): `ui/auth/AuthScreen.kt` ->
  `POST /api/users/set-username` (`auth/handler.go: SetUsername`).

### 2. Токены и прозрачное обновление

 Каждый защищённый запрос несёт `Authorization: Bearer <access>`.
Когда access протухает, клиент незаметно для пользователя меняет его по refresh-токену;
сервер при этом **ротирует** refresh (старый становится недействителен).


- Сервер: `POST /api/auth/refresh` -> `auth.RefreshToken` (валидирует refresh из
  Redis, удаляет старый, выдаёт новую пару). Защита маршрутов -
  `JWTAuthMiddleware` (`internal/auth/`), кладёт `user_id` в контекст запроса.
- Клиент: `data/remote/TokenAuthenticator.kt` - реактивно перехватывает `401`,
  дёргает refresh, повторяет запрос с новым токеном (с защитой от зацикливания).

### 3. Контакты и верификация личности (TOFU + QR)

 Чтобы написать кому-то, нужен его публичный identity-ключ.
Ключ привязывается к собеседнику при первом контакте (Trust On First Use). Для
защиты от MITM ключ можно сверить вне канала - через QR-код.


- Поиск/получение пользователя: `GET /api/users/search`, `GET /api/users/:userId`
  (`internal/user/handler.go`) - возвращают `identity_public_key` (base64).
- TOFU: при установке сессии libsignal сам сохраняет identity собеседника -
  `domain/crypto/SignalProtocolStoreImpl.kt` (`saveIdentity`).
- QR: `ui/qr/` (`MyQRScreen` - свой ключ, `QRScannerScreen` - сканирование),
  контакты - `ui/contacts/`.

### 4. Защищённая сессия (X3DH)

 Перед первым сообщением собеседнику устанавливается общая
секретная сессия по протоколу X3DH - асинхронно, даже если получатель сейчас
офлайн. Для этого отправитель скачивает «prekey-бандл» получателя; сервер при
выдаче атомарно «расходует» один одноразовый prekey из пула.


- Выдача бандла: `GET /api/keys/:userId` -> `internal/keys/service.go`
  (`GetPrekeyBundle`, расход OTK через `FOR UPDATE SKIP LOCKED`); остаток пула -
  `GET /api/keys/count`.
- Клиент: `CryptoManager.buildSession` -> `SessionBuilder.processPreKeyBundle`,
  состояние сессий хранится в `SignalProtocolStoreImpl` (Room).

### 5. Отправка сообщения (шифрование -> P2P -> fallback)

 Текст шифруется в рамках сессии Double Ratchet. Затем клиент
пробует доставить его напрямую по WebRTC DataChannel; если прямого соединения нет -
кладёт зашифрованный блоб на сервер для офлайн-доставки. Сервер видит только
шифртекст и тип сообщения (`PREKEY` - первое, `WHISPER` - последующие).


- Оркестрация: `data/repository/ChatRepository.kt` (`sendMessage`) - проверяет
  наличие сессии (`hasSessionWith`), при необходимости строит её (шаг 4),
  шифрует (`CryptoManager.encrypt`), выбирает канал.
- P2P-ветка: `WebRTCRepository.isConnected` -> `WebRTCManager.sendMessage`.
- Серверная ветка: `POST /api/messages/store` -> `internal/message/handler.go`
  (декодирует base64 -> `BYTEA`) -> `service.StoreMessage` (таблица
  `pending_messages`, поле `message_type`).

### 6. P2P-канал (WebRTC + Signaling)

 При открытии чата клиент пытается установить прямое соединение.
Через WebSocket-сигналинг стороны обмениваются `offer`/`answer` и ICE-кандидатами,
после чего поднимается DataChannel. Сервер-сигналинг только пересылает служебные
сообщения между онлайн-клиентами; если получатель офлайн - отвечает отправителю
`peer_offline`. NAT обходится через STUN/TURN (coturn).


- Инициация: `ui/chat/ChatViewModel.kt` (`initiateP2PConnection`) ->
  `WebRTCRepository.initiateConnection` -> `WebRTCManager.createPeerConnection`
  (создаёт DataChannel и `offer`). ICE-политика `GATHER_ONCE`: `offer/answer`
  буферизуются и уходят по `onIceGatheringChange(COMPLETE)`.
- WS-клиент: `domain/signaling/SignalingClient.kt`
  (`wss://<host>/ws?token=<jwt>`, экспоненциальный reconnect).
- Сервер-сигналинг: `cmd/signaling/main.go`, апгрейд и аутентификация по
  `?token` - `internal/signaling/handler.go`; маршрутизация по `To`, статусы
  онлайн и `peer_offline` - `internal/signaling/hub.go`; пер-клиентские
  read/write-помпы и пинги - `internal/signaling/client.go`.
- Формат сигналов: `SignalMessage { type, to, from, sdp, iceCandidate(s), error }`
  с типами `offer | answer | ice_candidate | error`.

### 7. Офлайн-доставка и получение

 Когда приложение получателя активно, оно забирает накопленные
сообщения с сервера, расшифровывает их (Double Ratchet продвигается на каждом
сообщении) и подтверждает получение, чтобы сервер их больше не отдавал. Входящие
P2P-сообщения расшифровываются на лету.


- Pull: `GET /api/messages/pending` (`message/handler.go` кодирует `BYTEA` ->
  base64) -> `ChatRepository.fetchPendingMessages` ->
  `CryptoManager.decrypt(senderId, ciphertext, messageType)` -> сохранение в Room.
- Подтверждение: `POST /api/messages/ack` -> `service.AckMessages`
  (`delivered = TRUE`).
- Входящее по P2P: `ChatViewModel` слушает `WebRTCRepository.messageFlow` ->
  `ChatRepository.handleP2PMessage`.
- Статус P2P для UI: `ChatViewModel.isP2PConnected` <-
  `WebRTCManager.connectionStateFlow`.

### 8. Криптография (Signal Protocol)

 Шифрование сквозное (E2EE) на базе библиотеки **libsignal**:
**X3DH** для установления сессии и **Double Ratchet** для потока сообщений. Это даёт
forward secrecy (компрометация текущего ключа не раскрывает прошлые сообщения) и
восстановление после компрометации. Приватные ключи и состояние сессий никогда не
покидают устройство; сервер хранит лишь публичные prekey и шифртексты.


- Генерация ключей, X3DH, шифрование/расшифровка: `domain/crypto/CryptoManager.kt`.
- Хранилище ключей/идентичностей/сессий (реализация `SignalProtocolStore` поверх
  Room): `domain/crypto/SignalProtocolStoreImpl.kt`, сущности - `data/local/`.
- Серверная часть ключей: `internal/keys/`, схема - `sql/init/schema.sql`
  (`signed_prekeys`, `one_time_prekeys`, `pending_messages.message_type`).


## Stack

| Backend | Android |
|---------|---------|
| Go 1.25, Gin | Kotlin, Jetpack Compose |
| PostgreSQL 16 | Room, Hilt |
| Redis 7 | libsignal (Signal Protocol) |
| coturn (TURN) | WebRTC (DataChannel) |
| Docker, Nginx | OkHttp, Retrofit |


## Features

- **E2EE** - Signal Protocol (X3DH + Double Ratchet). Сервер не расшифровывает сообщения.
- **P2P** - прямая передача через WebRTC DataChannel, если оба онлайн.
- **Офлайн-доставка** - зашифрованные сообщения хранятся на сервере до получения.
- **OTP-аутентификация** - вход по email без пароля, код живёт 5 минут.
- **QR-верификация** - обмен публичными ключами через QR для защиты от MITM.
- **JWT** - HS256, поддержка refresh-токена.


## Quick Start

### Backend

**Требования:** создать .env, Docker

```bash
cd p2p-server
docker compose up -d
```

Сервисы поднимутся на:
- REST API - `http://localhost:<specified_port>`
- Signaling - `http://localhost:<specified_port>`

Доступные команды:

```bash
make build        # собрать бинарники локально
make run-api      # запустить REST API без Docker
make run-sign     # запустить Signaling без Docker
```

### Android

1. Открыть `p2p-client/` в Android Studio.
2. В `di/AppModule.kt` заменить `baseUrl` на адрес своего сервера.
3. Запустить на устройстве или эмуляторе (API 26+).


## Environment Variables

| Переменная | Описание |
|------------|----------|
| `DB_HOST / PORT / USER / PASSWORD / NAME` | PostgreSQL |
| `REDIS_ADDR`, `REDIS_PASSWORD` | Redis |
| `JWT_SECRET` | Секрет для HS256 |
| `JWT_EXPIRATION_DAYS` | Срок жизни токена (по умолчанию 90) |
| `SMTP_HOST / PORT / USERNAME / PASSWORD` | SMTP для OTP |
| `TURN_REALM / USER / PASSWORD / EXTERNAL_IP` | coturn |
| `ENVIRONMENT` | `production` включает `gin.ReleaseMode` |


## Project Structure

```
.
├── p2p-server/          - Go backend (REST API + Signaling)
│   ├── cmd/             - точки входа сервисов
│   ├── internal/        - бизнес-логика (auth, user, message, signaling)
│   ├── pkg/             - jwt, email
│   ├── sql/             - DDL схема БД (psql)
│   └── docker-compose.yaml
└── p2p-client/          - Android-приложение
    └── app/src/main/java/com/p2p/
        ├── ui/          - Compose-экраны и ViewModel'ы
        ├── data/        - Room, Retrofit, репозитории
        └── domain/      - крипто, signaling, WebRTC
```


## Known Limitations

- Нет видео/аудио звонков.
- Один инстанс сервера, без горизонтального масштабирования.
- Нет push-уведомлений - сообщения подтягиваются при открытии приложения.
- TURN-сервер на том же хосте что и API.


## License

MIT
