# syntax=docker/dockerfile:1

# --- builder --------------------------------------------------------------
# Wheels are built in a throwaway stage so the runtime image carries neither a
# compiler toolchain nor the build cache.
FROM python:3.11-slim-bookworm AS builder

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /build

RUN apt-get update \
 && apt-get install --no-install-recommends -y build-essential libpq-dev \
 && rm -rf /var/lib/apt/lists/*

COPY backend/requirements.txt ./requirements.txt
COPY ml /ml

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

RUN pip install --upgrade pip setuptools wheel \
 && pip install -r requirements.txt \
 && pip install /ml

# --- runtime --------------------------------------------------------------
FROM python:3.11-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:$PATH"

RUN apt-get update \
 && apt-get install --no-install-recommends -y libpq5 curl \
 && rm -rf /var/lib/apt/lists/* \
 # The application never needs root, and a container that cannot write outside
 # its own data directory limits what a compromise reaches.
 && useradd --create-home --uid 10001 --shell /usr/sbin/nologin nutrilens

COPY --from=builder /opt/venv /opt/venv

WORKDIR /app
COPY --chown=nutrilens:nutrilens backend/app ./app
COPY --chown=nutrilens:nutrilens backend/migrations ./migrations
COPY --chown=nutrilens:nutrilens backend/alembic.ini ./alembic.ini

RUN mkdir -p /var/lib/nutrilens/meal-images \
 && chown -R nutrilens:nutrilens /var/lib/nutrilens

USER nutrilens

EXPOSE 8000

# Reports unhealthy when a dependency the API needs is down, so an orchestrator
# stops routing to a container that cannot actually serve.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl --fail --silent http://localhost:8000/ready || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
