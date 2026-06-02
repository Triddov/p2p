# -e = exit on error, pipefail = ловить ошибки в pipe
.SHELLFLAGS := -e -o pipefail -c
.PHONY: help build run-api run-sign migrate clean deploy
SHELL := bash

help:
	@echo "Available commands:"
	@echo "  make build              - Build both servers (local)"
	@echo "  make run-api            - Run REST API server"
	@echo "  make run-signaling      - Run Signaling server"
	@echo "  make migrate            - Run database migrations"
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
	psql -U postgres -d p2p_db -f sql/init/schema.sql

clean:
	rm -rf bin/ dist/

deploy:
	go vet ./...
	go test ./...
	docker buildx build --platform linux/amd64 --push -t triddov/p2p-api:latest -f Dockerfile.api .
	docker buildx build --platform linux/amd64 --push -t triddov/p2p-signaling:latest -f Dockerfile.signaling .
	scp docker-compose.yaml serv:~/apps/p2p-backend
	ssh serv "~/apps/p2p-backend/start_script.sh"
