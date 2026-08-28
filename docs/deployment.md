# Deployment

## Local development

```bash
cp .env.example .env
make secret                 # paste into NUTRILENS_JWT_SECRET
make setup
make up                     # API, PostgreSQL, Redis
```

`make up` applies migrations and seeds the food catalog before starting the
server, so a fresh volume comes up with the current schema instead of failing on
the first query.

- API: <http://localhost:8000>
- Docs: <http://localhost:8000/docs>
- Health: <http://localhost:8000/health>

Without Docker:

```bash
make setup
make migrate
make seed
make run
```

## Pointing the app at a backend

The base URL is injected through the `@ApiBaseUrl` qualifier, so build variants
and tests can point elsewhere. For a backend running on the development machine:

```bash
adb reverse tcp:8000 tcp:8000     # then use http://localhost:8000/
```

The network security config permits cleartext to loopback and the emulator host
(`10.0.2.2`) only. Everything else is HTTPS.

## Backend in production

### Requirements

| | Minimum |
|---|---|
| PostgreSQL | 14+ |
| Redis | 7+ (optional, but see below) |
| Python | 3.11 |
| TLS | terminated in front of the API |

### Configuration

Required:

```bash
NUTRILENS_ENVIRONMENT=production
NUTRILENS_JWT_SECRET=<48+ random characters>
NUTRILENS_DATABASE_URL=postgresql+psycopg://user:pass@host:5432/nutrilens
```

A production-like environment refuses to start if the secret is missing or short,
if the database is SQLite, or if debug mode is on. The interactive docs are not
served.

Strongly recommended:

```bash
NUTRILENS_REDIS_URL=redis://host:6379/0
```

Without it each worker process gets its own rate-limit budget, so the effective
limit is the configured value multiplied by the worker count.

### Running

```bash
docker build -f backend/Dockerfile -t nutrilens-backend:0.1.0 .
docker run -d -p 8000:8000 --env-file .env nutrilens-backend:0.1.0
```

The image runs as a non-root user (uid 10001) and carries no compiler
toolchain -- wheels are built in a discarded stage.

Migrations run as a separate step, not on startup:

```bash
docker run --rm --env-file .env nutrilens-backend:0.1.0 alembic upgrade head
docker run --rm --env-file .env nutrilens-backend:0.1.0 python -m app.seed
```

Several replicas starting at once would otherwise race to migrate the same
database.

### Probes

| Path | Probe | Failure means |
|---|---|---|
| `/health` | liveness | the process is wedged; restart it |
| `/ready` | readiness | it cannot serve; stop routing to it |

Redis is reported in `/ready` but does not gate readiness, because the rate
limiter fails open.

### Scaling

The API is stateless per request, so replicas scale horizontally. Two things
must be shared:

- **Redis**, or each process rate-limits independently.
- **Object storage.** `LocalObjectStorage` does not survive more than one node;
  implement `ObjectStorage` against S3, GCS or Azure Blob before scaling out.
  Nothing above that interface changes.

## Building the APK

### Prerequisites

- JDK 17
- Android SDK with platform 35 and build-tools 35
- `ANDROID_HOME` set, or build from Android Studio

### Debug

```bash
cd android
./gradlew assembleDebug
# android/app/build/outputs/apk/debug/app-debug.apk
```

Install it:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Release

Create a keystore once:

```bash
keytool -genkeypair -v \
  -keystore nutrilens-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias nutrilens
```

**Never commit it.** `*.jks` and `*.keystore` are git-ignored.

Put the coordinates in `~/.gradle/gradle.properties`:

```properties
NUTRILENS_KEYSTORE_PATH=/absolute/path/nutrilens-release.jks
NUTRILENS_KEYSTORE_PASSWORD=...
NUTRILENS_KEY_ALIAS=nutrilens
NUTRILENS_KEY_PASSWORD=...
```

```bash
cd android
./gradlew assembleRelease
# android/app/build/outputs/apk/release/app-release.apk
```

Release builds enable R8 with resource shrinking. When no keystore is
configured the release build falls back to the debug key so `assembleRelease`
still produces an installable APK for review; a store release must supply the
real key.

The `release-apk` workflow does this in CI, restoring the keystore from a
secret and deleting it afterwards.

### Verifying

```bash
$ANDROID_HOME/build-tools/35.0.0/apkanalyzer apk summary app-release.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose app-release.apk
```

## CI

| Job | Needs the Android SDK |
|---|---|
| Python lint | no |
| ML tests | no |
| Backend tests + migrations against PostgreSQL | no |
| Android domain module | no |
| Android build, lint, APK | yes |
| Backend image build and smoke test | no |

The domain-module job compiles and tests `android/core/model` with a plain JDK.
It is not a substitute for the full Android build, which the `android` job runs.

The backend job applies the migration to a real PostgreSQL instance and then
rolls it back, because a migration that cannot be reversed cannot be safely
deployed.

## Operational notes

**Logs** are JSON by default, one object per line, with `request_id` on every
entry so a user report maps onto a server-side trace.

**Backups.** `pg_dump` on a schedule; restore-test them. Object storage needs
its own lifecycle policy, and meal photographs are the most sensitive data in
the system.

**Retention.** Soft-deleted rows are kept so an in-flight sync cannot resurrect
them. A purge schedule for them **is not set by this repository** and must be
defined before any real deployment.

**Rotating the JWT secret** invalidates every session, which is the intended way
to force a global sign-out.
