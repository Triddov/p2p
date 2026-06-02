# Point & Point — P2P Messenger

![Release](https://img.shields.io/github/actions/workflow/status/Triddov/p2p/release.yml?branch=main&label=release&logo=github)
![Deploy](https://img.shields.io/github/actions/workflow/status/Triddov/p2p/deploy.yml?branch=main&label=deploy&logo=github)
![Go](https://img.shields.io/badge/Go-1.25-00ADD8?logo=go&logoColor=white)
![Android](https://img.shields.io/badge/Android-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Docker](https://img.shields.io/docker/image-size/triddov/p2p-api/latest?label=api%20image&logo=docker)
![Docker](https://img.shields.io/docker/image-size/triddov/p2p-signaling/latest?label=api%20image&logo=docker)
![License](https://img.shields.io/badge/license-MIT-blue)

Гибридный P2P-мессенджер с end-to-end шифрованием. Сообщения передаются напрямую между устройствами через WebRTC DataChannel; если получатель офлайн — сообщение хранится на сервере в зашифрованном виде до доставки. Сервер не имеет доступа к ключам шифрования.

>  Курсовой проект


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
│                   │      │                     │
└────────┬──────────┘      └──────────┬──────────┘
         │                            │
         ▼                            ▼
┌─────────────────────────────────────────────────────────┐
│                     PostgreSQL                          │
└─────────────────────────────────────────────────────────┘
         ▲
┌────────┴──────────┐      ┌─────────────────────┐
│   Redis           │      │  coturn (TURN)      │
│   rate limiting   │      │  UDP/TCP            │
│   OTP codes       │      │                     │
└───────────────────┘      └─────────────────────┘
```


## Stack

| Backend | Android |
|---------|---------|
| Go 1.25, Gin | Kotlin, Jetpack Compose |
| PostgreSQL 16 | Room, Hilt |
| Redis 7 | libsignal (Signal Protocol) |
| coturn (TURN) | WebRTC (DataChannel) |
| Docker, Nginx | OkHttp, Retrofit |


## Features

- **E2EE** — Signal Protocol (X3DH + Double Ratchet). Сервер не расшифровывает сообщения.
- **P2P** — прямая передача через WebRTC DataChannel, если оба онлайн.
- **Офлайн-доставка** — зашифрованные сообщения хранятся на сервере до получения.
- **OTP-аутентификация** — вход по email без пароля, код живёт 5 минут.
- **QR-верификация** — обмен публичными ключами через QR для защиты от MITM.
- **JWT** — HS256, поддержка refresh-токена.


## Quick Start

### Backend

**Требования:** Docker, Docker Compose.

```bash
cd p2p-server
cp .env.example .env 
docker compose up -d
```

Сервисы поднимутся на:
- REST API — `http://localhost:<specified_port>`
- Signaling — `http://localhost:<specified_port>`

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
├── p2p-server/          — Go backend (REST API + Signaling)
│   ├── cmd/             — точки входа сервисов
│   ├── internal/        — бизнес-логика (auth, user, message, signaling)
│   ├── pkg/             — jwt, email
│   ├── sql/             — DDL схема БД (psql)
│   └── docker-compose.yaml
└── p2p-client/          — Android-приложение
    └── app/src/main/java/com/p2p/
        ├── ui/          — Compose-экраны и ViewModel'ы
        ├── data/        — Room, Retrofit, репозитории
        └── domain/      — крипто, signaling, WebRTC
```


## Known Limitations

- Нет видео/аудио звонков.
- Один инстанс сервера, без горизонтального масштабирования.
- Нет push-уведомлений — сообщения подтягиваются при открытии приложения.
- TURN-сервер на том же хосте что и API.


## License

MIT
