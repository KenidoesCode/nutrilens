# NutriLens developer commands.
#
# Everything here works from a clean checkout. Targets that need the Android SDK
# say so, and the ones that do not are the ones CI runs on every push.

SHELL := /bin/bash
.DEFAULT_GOAL := help

PYTHON ?= python3
VENV := .venv
VENV_BIN := $(VENV)/bin
GRADLE ?= ./gradlew

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-24s\033[0m %s\n", $$1, $$2}'

# --- setup ----------------------------------------------------------------

.PHONY: setup
setup: ## Create the virtualenv and install backend + ml in editable mode
	$(PYTHON) -m venv $(VENV)
	$(VENV_BIN)/pip install --upgrade pip setuptools wheel
	$(VENV_BIN)/pip install -e ./ml[dev]
	$(VENV_BIN)/pip install -r backend/requirements-dev.txt

.PHONY: secret
secret: ## Print a value suitable for NUTRILENS_JWT_SECRET
	@$(PYTHON) -c "import secrets; print(secrets.token_urlsafe(48))"

# --- python ---------------------------------------------------------------

.PHONY: test
test: ## Run the ml and backend test suites
	$(VENV_BIN)/python -m pytest

.PHONY: test-ml
test-ml: ## Run the ml test suite only
	$(VENV_BIN)/python -m pytest ml/tests

.PHONY: test-backend
test-backend: ## Run the backend test suite only
	$(VENV_BIN)/python -m pytest backend/tests

.PHONY: coverage
coverage: ## Run the suites with a coverage report
	$(VENV_BIN)/python -m pytest \
		--cov=ml/nutrilens_ml --cov=backend/app --cov-report=term-missing

.PHONY: lint
lint: ## Lint the Python sources
	$(VENV_BIN)/ruff check ml backend

.PHONY: format
format: ## Apply the formatter and import ordering
	$(VENV_BIN)/ruff check --fix ml backend
	$(VENV_BIN)/black --line-length 100 ml backend

# --- backend --------------------------------------------------------------

.PHONY: run
run: ## Run the API against the local database
	$(VENV_BIN)/uvicorn app.main:app --reload --app-dir backend

.PHONY: migrate
migrate: ## Apply database migrations
	cd backend && PYTHONPATH=. ../$(VENV_BIN)/python -m alembic upgrade head

.PHONY: migration
migration: ## Autogenerate a migration: make migration MESSAGE="add x"
	cd backend && PYTHONPATH=. ../$(VENV_BIN)/python -m alembic revision \
		--autogenerate -m "$(MESSAGE)"

.PHONY: seed
seed: ## Load the food catalog into the database
	cd backend && PYTHONPATH=. ../$(VENV_BIN)/python -m app.seed

.PHONY: up
up: ## Start the full stack (API, PostgreSQL, Redis)
	docker compose up --build

.PHONY: down
down: ## Stop the stack and remove its volumes
	docker compose down -v

# --- android --------------------------------------------------------------
# These need the Android SDK. Point ANDROID_HOME at it, or use Android Studio.

.PHONY: android-test
android-test: ## Run the Android unit tests (requires the Android SDK)
	cd android && $(GRADLE) test

.PHONY: android-lint
android-lint: ## Run Android Lint (requires the Android SDK)
	cd android && $(GRADLE) lint

.PHONY: apk
apk: ## Build the debug APK (requires the Android SDK)
	cd android && $(GRADLE) assembleDebug
	@echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"

.PHONY: apk-release
apk-release: ## Build the release APK (requires the Android SDK and signing config)
	cd android && $(GRADLE) assembleRelease
	@echo "APK: android/app/build/outputs/apk/release/app-release.apk"

.PHONY: verify-domain
verify-domain: ## Compile and test the pure-Kotlin domain module (no Android SDK needed)
	bash scripts/verify-domain-module.sh

# --- everything -----------------------------------------------------------

.PHONY: check
check: lint test verify-domain ## Everything that runs without the Android SDK
