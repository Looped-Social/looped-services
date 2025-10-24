.PHONY: help up down logs database db datbase dev test build jar clean psql redis-ping

# Prefer Docker Compose v2 (docker compose); fall back to v1 (docker-compose)
COMPOSE := $(shell docker compose version >/dev/null 2>&1 && echo "docker compose" || echo "docker-compose")

help:
	@echo "Targets:"
	@echo "  up            Start Postgres + Redis via docker-compose"
	@echo "  down          Stop compose services"
	@echo "  logs          Tail compose logs"
	@echo "  database      Start only Postgres (compose)"
	@echo "  db            Alias for database"
	@echo "  datbase       Alias for database (typo-friendly)"
	@echo "  dev           Run API in dev (Spring Boot)"
	@echo "  test          Run tests (uses Testcontainers)"
	@echo "  build         Package modules (skip tests)"
	@echo "  jar           Run packaged API JAR"
	@echo "  clean         Clean build artifacts"
	@echo "  psql          psql into local Postgres"
	@echo "  redis-ping    Ping local Redis"

up:
	$(COMPOSE) up -d

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f

database db datbase:
	$(COMPOSE) up -d postgres

dev:
	./mvnw -q -pl apps/api -am spring-boot:run

test:
	./mvnw -q -T 1C test

build:
	./mvnw -q -T 1C -DskipTests package

jar:
	java -jar apps/api/target/looped-api-0.0.1-SNAPSHOT.jar

clean:
	./mvnw -q clean

psql:
	psql -h localhost -U looped -d looped

redis-ping:
	redis-cli -h localhost -p 6379 ping

