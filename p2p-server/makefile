# -e = exit on error, pipefail = ловить ошибки в pipe
.SHELLFLAGS := -e -o pipefail -c
.PHONY: help build run-api run-sign migrate migrate-down migrate-create migrate-version migrate-force clean deploy
SHELL := bash

# golang-migrate CLI:
#   go install -tags 'postgres' github.com/golang-migrate/migrate/v4/cmd/migrate@latest
# Примечание: API сам применяет миграции на старте (RunMigrations). Эти таргеты —
# для ручного/локального прогона и создания новых миграций.
MIGRATIONS_DIR := sql/migrations
# Переопредели при вызове: make migrate DB_URL="postgres://user:pass@host:5432/db?sslmode=disable"
DB_URL ?= postgres://postgres:postgres@localhost:5432/p2p_db?sslmode=disable

help:
	@echo "Available commands:"
	@echo "  make build              - Build both servers (local)"
	@echo "  make run-api            - Run REST API server"
	@echo "  make run-signaling      - Run Signaling server"
	@echo "  make migrate            - Apply all pending migrations (up)"
	@echo "  make migrate-down       - Roll back the last migration"
	@echo "  make migrate-create name=<desc> - Create a new migration pair"
	@echo "  make migrate-version    - Show current schema version"
	@echo "  make migrate-force V=<n> - Set version (fix dirty / baseline)"
	@echo "  make clean              - Remove binaries and images"
	@echo "  make deploy             - Build Docker images for linux/amd64, push them to registry and start on prod"

build:
	go build -o bin/api cmd/api/main.go
	go build -o bin/signaling cmd/signaling/main.go

run-api:
	go run cmd/api/main.go

run-sign:
	go run cmd/signaling/main.go

migrate:
	migrate -path $(MIGRATIONS_DIR) -database "$(DB_URL)" up

migrate-down:
	migrate -path $(MIGRATIONS_DIR) -database "$(DB_URL)" down 1

migrate-create:
	@test -n "$(name)" || { echo "usage: make migrate-create name=<desc>"; exit 1; }
	migrate create -ext sql -dir $(MIGRATIONS_DIR) -seq $(name)

migrate-version:
	migrate -path $(MIGRATIONS_DIR) -database "$(DB_URL)" version

migrate-force:
	@test -n "$(V)" || { echo "usage: make migrate-force V=<version>"; exit 1; }
	migrate -path $(MIGRATIONS_DIR) -database "$(DB_URL)" force $(V)

clean:
	rm -rf bin/ dist/

deploy:
	go vet ./...
	go test ./...
	docker buildx build --platform linux/amd64 --push -t triddov/p2p-api:latest -f Dockerfile.api .
	docker buildx build --platform linux/amd64 --push -t triddov/p2p-signaling:latest -f Dockerfile.signaling .
	scp docker-compose.yaml serv:~/apps/p2p-backend
	ssh serv "~/apps/p2p-backend/start_script.sh"
