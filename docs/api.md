# API

Base path `/api/v1`. Interactive documentation is served at `/docs` outside
production, and the OpenAPI schema at `/openapi.json`.

## Conventions

**Versioning.** The version is in the path. A breaking change means `/api/v2`
served alongside `v1`, because a mobile client cannot be forced to update.

**Errors.** Every failure has the same shape:

```json
{
  "error": {
    "code": "INVALID_IMAGE",
    "message": "The uploaded image could not be processed.",
    "request_id": "9f1c2a5e7b0d4c3f"
  }
}
```

Clients switch on `code`, never on `message`. Stack traces, database messages
and internal identifiers never cross the boundary; an unexpected exception is
logged in full server-side and returned as a generic `INTERNAL_ERROR`.

Validation failures name the offending fields and their failure type, but never
echo the submitted values -- that would put passwords and image bytes into error
responses and, from there, into client logs.

**Request ids.** Every response carries `X-Request-ID`. A client-supplied one is
honoured only if it is short and alphanumeric; anything else is discarded,
because echoing arbitrary input into headers and logs invites injection.

**Authentication.** `Authorization: Bearer <access_token>` on everything except
registration, login and refresh.

## Error codes

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_FAILED` | 422 | the request did not validate |
| `INVALID_CREDENTIALS` | 401 | wrong email or password |
| `NOT_AUTHENTICATED` | 401 | missing or invalid access token |
| `TOKEN_EXPIRED` / `TOKEN_INVALID` | 401 | the session is over |
| `FORBIDDEN` | 403 | not permitted |
| `NOT_FOUND` | 404 | no such record, or not yours |
| `EMAIL_ALREADY_REGISTERED` | 409 | that address has a live account |
| `WEAK_PASSWORD` | 422 | fails the password policy |
| `IMAGE_TOO_LARGE` | 413 | above the upload limit |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | not JPEG, PNG or WebP |
| `INVALID_IMAGE` | 422 | unreadable or corrupt |
| `ANALYSIS_FAILED` | 422 | the pipeline could not produce a result |
| `RATE_LIMITED` | 429 | too many requests; see `Retry-After` |
| `INTERNAL_ERROR` | 500 | a bug |
| `SERVICE_UNAVAILABLE` | 503 | a dependency is down |

Note that a record belonging to another user returns `404`, not `403`.
Confirming that an id exists would leak information about other accounts.

## Authentication

### `POST /auth/register` -> `201`

```json
{
  "email": "person@example.com",
  "password": "correct-horse-battery-1",
  "display_name": "Person",
  "timezone": "Asia/Kolkata",
  "locale": "en"
}
```

Returns an access/refresh pair. The timezone is required and validated against
the IANA database -- the chrononutrition features are meaningless without it.

### `POST /auth/login` -> `200`

An unknown email and a wrong password return an identical response, and the
server verifies against a dummy hash on the unknown-email path so the two take
comparable time. Distinguishable responses would let an attacker enumerate
registered addresses.

### `POST /auth/refresh` -> `200`

Rotation is unconditional: the presented refresh token is revoked whether or not
the caller ever sees the response, so a stolen token is usable at most once.

### `POST /auth/logout` -> `204`

Returns `204` whether or not the token was still valid. Telling a caller their
token was already dead is information they do not need.

## Meals

### `POST /meals` -> `201`, or `200` on replay

```json
{
  "consumed_at": "2026-05-01T08:42:00+05:30",
  "timezone": "Asia/Kolkata",
  "meal_type": "breakfast",
  "idempotency_key": "device-a-0001",
  "items": [
    {
      "display_name": "Rice",
      "category": "solid",
      "estimated_volume_ml": 180.0,
      "recognition_confidence": 0.62,
      "portion_confidence": 0.65,
      "portion_method": "reference-object"
    }
  ]
}
```

`consumed_at` must carry a UTC offset; a naive timestamp is rejected, because it
cannot be placed on a user's day.

Send an `idempotency_key`. A replayed key returns the original meal with `200`
rather than `201` and creates nothing.

The client sends a **volume**. The server derives the mass from its own density
table and returns it.

### `GET /meals` -> `200`

Paginated, newest first, `limit` (max 100) and `offset`, with an optional
`start`/`end` UTC range.

```json
{
  "items": [ ... ],
  "meta": { "total": 42, "limit": 20, "offset": 0, "has_more": true }
}
```

### `PATCH /meals/items/{item_id}/portion` -> `200`

```json
{ "corrected_volume_ml": 120.0 }
```

Recomputes mass and nutrition, preserves `original_mass_g`, and appends to the
item's portion-estimate history.

**This is the only way an edit reaches the server.** Re-sending the whole meal
would carry its original idempotency key, which makes it a replay: the server
returns the stored meal unchanged and the correction is silently lost. See
[offline-sync.md](offline-sync.md#why-edits-do-not-go-through-the-same-path).

### `PATCH /meals/items/{item_id}/name` -> `200`

Correcting a misidentified food re-resolves its density and recomputes the mass.
Carrying the previous food's density over would be a number about a food the
user just said this is not.

## Analysis

### `POST /analysis/meal-image` -> `200`

`multipart/form-data`:

| Field | Notes |
|---|---|
| `image` | JPEG, PNG or WebP, up to 12 MB |
| `store_image` | whether the server retains the photograph after inference |
| `reference_name` | optional; all three reference fields together or none |
| `reference_real_area_cm2` | real area of an object of known size in frame |
| `reference_image_area_ratio` | its share of the frame |

```json
{
  "prediction_id": "6f0f3f6a-6d29-4a1c-90a3-1a5f2a4bd6c1",
  "items": [
    {
      "name": "Rice",
      "category": "solid",
      "confidence": 0.62,
      "confidence_band": "medium",
      "estimated_volume_ml": 180.0,
      "estimated_mass_g": 153.0,
      "portion_confidence": 0.65,
      "portion_method": "reference-object",
      "overall_confidence": 0.28,
      "density_g_per_ml": 0.85,
      "density_source": "nutrilens-food-catalog@2024.1",
      "is_fallback_density": false,
      "nutrition": { "energy_kcal": 198.9, "protein_g": 4.13, "...": 0 }
    }
  ],
  "engine": "heuristic-color-texture",
  "model_version": "1.0.0+catalog-2024.1",
  "processing_ms": 79,
  "warnings": [],
  "estimates_are_approximate": true
}
```

`estimates_are_approximate` is always `true` and is a permanent part of the
contract. **A client that renders a mass without also rendering its confidence
is misusing this API.**

Detecting nothing is a success with `items: []` and
`warnings: ["no_food_detected"]`, not an error. The pipeline refusing to invent
food is correct behaviour.

The upload is read in bounded chunks and rejected the moment it exceeds the
limit, so an oversized file is never buffered in full.

## Synchronisation

### `POST /sync/push` -> `200`

A batch of queued client operations, each with its own idempotency key.

```json
{
  "operations": [
    { "idempotency_key": "op-00000001", "operation": "create_meal", "meal": { } },
    { "idempotency_key": "op-00000002", "operation": "delete_meal", "meal_id": "..." }
  ]
}
```

```json
{
  "results": [
    {
      "idempotency_key": "op-00000001",
      "status": "applied",
      "entity_id": "6f0f...",
      "meal": { "id": "6f0f...", "items": [{ "id": "a1b2...", "...": 0 }] }
    },
    { "idempotency_key": "op-00000002", "status": "replayed", "entity_id": "..." }
  ],
  "applied": 1, "replayed": 1, "failed": 0,
  "server_time": "2026-05-01T12:00:00Z"
}
```

A `create_meal` result carries the **stored meal**, not just its id, and so does
a replay. The client needs the server's item ids: they are the only way to
address an individual item in a later correction, and a client that lost the
original response would otherwise be permanently unable to send one.

**Each operation is validated and applied independently.** One malformed meal
reports `failed` while the rest still apply. This is the whole point of
per-operation keys: a device holding one bad record must never be unable to sync
any of its good ones. The embedded meal is therefore deliberately not validated
at the batch schema level, which would return `422` for the entire request.

### `GET /sync/pull` -> `200`

`since` (exclusive lower bound on `updated_at`) and `limit`.

```json
{
  "meals": [ ... ],
  "deleted_meal_ids": ["..."],
  "next_cursor": "2026-05-01T12:00:00Z",
  "has_more": false,
  "server_time": "2026-05-01T12:00:00Z"
}
```

Deletions are reported explicitly rather than silently omitted -- a row that just
vanished from the feed would leave a stale copy on the device forever.

## Analytics

`GET /analytics/today`, `GET /analytics/range`, `GET /analytics/nutrition`.

A logical day starts at **04:00 local time**, not midnight: a meal at 00:30
belongs to the evening that preceded it.

```json
{
  "day": "2026-05-01",
  "meal_count": 3,
  "first_meal_local": "2026-05-01T08:42:00+05:30",
  "last_meal_local": "2026-05-01T19:16:00+05:30",
  "eating_window_minutes": 634,
  "fasting_minutes": 806
}
```

`null` means "not enough information", which is distinct from zero. A day with
one meal has no window at all, and `eating_window_consistency` is `null` below
two measurable days rather than a flattering `1.0`.

Ranges are capped at 90 days.

## Profile and data rights

| Endpoint | Purpose |
|---|---|
| `GET /users/me` | the current profile |
| `PATCH /users/me` | display name, timezone, locale |
| `GET /users/me/export` | every meal record on the account, as JSON |
| `DELETE /users/me` | deactivate, revoke every session, mark data deleted |

The export contains no credentials and no tokens, which the test suite asserts
by scanning the response.

## Operations

| Endpoint | Meaning |
|---|---|
| `GET /health` | the process is alive -- restart me if not |
| `GET /ready` | it can serve traffic -- route to me if so |

They answer different questions and must not be conflated. A database outage
makes a pod unready, not dead. Redis backs rate limiting only, and that limiter
fails open, so a Redis outage does not take the API out of rotation.

## Rate limits

| Scope | Default |
|---|---|
| global, per client address | 60 requests / 60 s |
| `/auth/*` | 10 requests / 300 s |

The auth budget is far tighter because the global one is much too generous to
slow down password guessing. Health endpoints are never throttled -- a throttled
probe would report a healthy service as down.
