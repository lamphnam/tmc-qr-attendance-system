# Convenience targets. Needs GNU make (Linux/macOS/WSL/Git Bash). On plain Windows PowerShell run the commands in
# README.md directly - every target below is just a thin wrapper around them.
#
# JDK 21 must be the JDK Maven uses:  export JAVA_HOME=/path/to/jdk-21

.PHONY: help env test test-java test-frontend test-mocks config build up down reset logs ps smoke integration check-java verify docs-check

help:
	@echo "make env          copy .env.example to .env (never overwrites)"
	@echo "make test         unit + context tests: Maven (all 5 Java modules), frontend, mocks"
	@echo "make config       docker compose config --quiet"
	@echo "make up           build and start the whole stack, waits until every healthcheck passes"
	@echo "make smoke        scripts/smoke.sh against the running stack"
	@echo "make integration  node --test tests/integration/slice.test.mjs (stops/restarts some containers)"
	@echo "make verify       everything, one honest report (scripts/verify.mjs; add --fast via node directly)"
	@echo "make docs-check   markdown links/anchors/duplicates (scripts/check-docs.mjs)"
	@echo "make down         stop the stack, KEEP data"
	@echo "make reset        stop the stack and DESTROY all data volumes (central DB, kiosk SQLite, mock HR)"

env:
	@test -f .env && echo ".env already exists, left untouched" || (cp .env.example .env && echo "created .env from .env.example")

check-java:
	@mvn -v | grep -q "Java version: 21" || (echo "Maven is not running on JDK 21. Set JAVA_HOME to a JDK 21." && exit 1)

test: test-java test-frontend test-mocks

test-java: check-java
	mvn -B -ntp test

test-frontend:
	cd web-frontend && npm ci && npm run typecheck && npm test && npm run build

test-mocks:
	cd mock-external-systems/mock-turnstile-api && npm test
	cd mock-external-systems/mock-hr-payroll-api && npm test

config:
	docker compose config --quiet

build:
	docker compose build

up:
	docker compose up --build -d --wait

down:
	docker compose down

reset:
	docker compose down -v

logs:
	docker compose logs --tail=100 -f

ps:
	docker compose ps

smoke:
	bash scripts/smoke.sh

integration:
	node --test tests/integration/slice.test.mjs

verify:
	node scripts/verify.mjs

docs-check:
	node scripts/check-docs.mjs
