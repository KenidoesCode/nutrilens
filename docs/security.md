# Security

A person's meal history is sensitive: it reveals religion, health conditions,
pregnancy, eating disorders and daily routine. This document states what is
protected, how, and what is deliberately not claimed.

**No security audit or penetration test has been performed on this code.**

## Threat model

| Threat | Mitigation | Residual risk |
|---|---|---|
| Stolen or lost phone | tokens in Keystore-backed encrypted storage; nothing in cloud backup | an unlocked phone gives full access |
| Network interception | TLS enforced; cleartext refused by config | none beyond the TLS trust store |
| Database dump | passwords bcrypt-hashed; refresh tokens stored only as SHA-256 fingerprints | meal history is readable |
| Account enumeration | identical response and comparable timing for unknown email vs wrong password | -- |
| Credential stuffing | 10 auth attempts / 5 min per address; password policy | distributed attacks |
| Session theft | 30-min access tokens; refresh rotation on every use | a token is usable within its window |
| Malicious upload | size, MIME, format, dimension and pixel-count validation before decode | -- |
| Path traversal via storage keys | keys generated server-side and resolved inside the storage root | -- |
| Log leakage | redaction processor on every log event | -- |
| SQL injection | SQLAlchemy bound parameters throughout | -- |

## Mobile

### Session storage

Tokens live in `EncryptedSharedPreferences` under a master key held in the
hardware-backed Android Keystore. Both keys and values are encrypted, so the
ciphertext is useless off the device even with filesystem access.

DataStore is used for ordinary preferences elsewhere in the same module; it has
no encrypted variant, so the token store deliberately uses a different mechanism
rather than a less protected one.

Sign-out uses `commit()`, not `apply()`: there must be no window in which the
tokens are still on disk after the UI says the user is signed out.

### Nothing is backed up

```xml
<full-backup-content>
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="file" path="." />
</full-backup-content>
```

Neither cloud backup nor device transfer carries any NutriLens data. Dietary
history does not belong in a backup the user did not explicitly ask for, and the
Keystore-wrapped session key would not survive a restore to another device
anyway.

### Transport

```xml
<base-config cleartextTrafficPermitted="false">
```

Cleartext is refused everywhere. A misconfigured base URL fails loudly rather
than sending bearer tokens and meal photographs in the clear. The only exception
is loopback, so a developer can point a debug build at a backend on their own
machine; it grants nothing to any remote host.

### Photographs

The image pipeline strips metadata before anything else sees the frame, by
rebuilding the image from raw pixel bytes after applying the orientation. That
drops EXIF, ICC and XMP in one step rather than enumerating what to remove --
and camera frames routinely carry GPS coordinates and a device identifier.

Processed images are written to app-private internal storage, which other apps
cannot read. The raw capture is deleted as soon as it has been processed, so the
unprocessed frame does not linger in the cache.

**Remote storage is opt-in and off by default.** The photograph is always sent
for inference; the setting controls only whether the server keeps it afterwards.
The distinction is stated in the setting's own explainer text.

### Sign-out erases everything

Meals, cached foods, queued operations and stored photographs are all removed. A
shared device must not leave one person's dietary history readable by the next
person to sign in. The chosen interface language is deliberately kept -- it is a
property of the device's user, not of the session.

### Secrets are not in the repository

No signing keystore, no API key, no `google-services.json`. Release signing
reads Gradle properties supplied by a local `gradle.properties` or the CI secret
store, and the release workflow restores the keystore from a secret for the
build and deletes it in an `always()` step.

## Backend

### Passwords

bcrypt via passlib, salted per password. Policy:

| Rule | Reason |
|---|---|
| at least 10 characters | -- |
| at most 72 bytes | bcrypt silently truncates beyond that; accepting more gives a false sense of strength |
| not in a common-password list | -- |
| at least 4 distinct characters | rejects `aaaaaaaaaaaa` |
| not all digits | ~3.3 bits per character, and dominant in breach corpora |

Verification never raises on a malformed hash; it returns `false`.

### Tokens

| | TTL | Storage |
|---|---|---|
| access | 30 min | stateless JWT |
| refresh | 30 days | JWT, with a revocable server-side record |

The refresh token's `jti` is stored **only as a SHA-256 fingerprint**, so a
database dump does not hand an attacker usable session identifiers.

Token type is checked explicitly on decode: a refresh token is never accepted
where an access token is required, and the test suite asserts it.

`NUTRILENS_JWT_SECRET` has no default outside development. A production-like
environment refuses to start without it, and refuses a secret shorter than 32
characters.

The client's `TokenAuthenticator` distinguishes three refresh outcomes:
`Refreshed`, `Rejected` (the server said no -- the session is over) and
`Unavailable` (network failure, 5xx). Only `Rejected` clears the session.
Treating a dropped connection as a rejection would sign users out over a problem
that resolves itself.

### Uploads

Validated in this order, cheapest first: byte size, declared MIME, sniffed
format, dimensions, pixel-count ceiling. The declared content type is never
trusted on its own -- a BMP labelled `image/jpeg` is rejected on the sniffed
format, which the test suite asserts.

Reading is chunked and aborts the moment the limit is exceeded, so an oversized
upload is never buffered in full.

### Object storage

Keys are generated server-side, date-partitioned and include a content digest.
`LocalObjectStorage` resolves every key inside the storage root and rejects
anything that escapes it -- keys are generated internally, but this is the
boundary where a crafted key would become a filesystem path, so it is enforced
here rather than trusted upstream.

Writes go to a temporary name and are renamed into place, so a reader never
observes a partially written object.

### Logging

A redaction processor runs on **every** log event, so a secret cannot reach the
pipeline even when a caller passes it by mistake:

```
password, new_password, current_password, password_hash,
token, access_token, refresh_token, authorization,
jwt_secret, secret, api_key,
image_bytes, image_data, email
```

Email addresses are redacted too: they identify a person, and a log line does not
need one to be useful.

Raw image bytes are never logged. An inference failure logs the engine's error
code and a truncated content hash, never the engine's internal message, which
can name file paths.

### Rate limiting

Redis-backed when configured, per-process otherwise. The Redis limiter **fails
open**: if Redis is unreachable the request proceeds. A rate-limiter outage must
not become a full outage, and that trade-off is a stated decision rather than an
accident.

The per-process fallback gives each worker its own budget. That is documented
rather than hidden, and Redis is the answer for any deployment running more than
one process.

### CORS

Empty by default, so no CORS headers are sent at all -- correct when only the
mobile app calls the API. Origins are configured explicitly per deployment.

### Response headers

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'
Cross-Origin-Resource-Policy: same-origin
```

The API serves no HTML, so the policy denies everything; that turns any
accidental HTML response into an inert document.

## Audit trail

`audit_events` records authentication, account changes and data export/erasure:
who, what, when, the request id and a truncated client hint. It contains no
credentials and no meal content.

Failed logins for unknown addresses are recorded with a null user id -- an
attempt against an address with no account is exactly what a defender needs to
see.

## Data rights

| Right | Endpoint | Behaviour |
|---|---|---|
| Access | `GET /users/me/export` | every meal record as JSON |
| Erasure | `DELETE /users/me` | deactivates, revokes every session, marks data deleted |

Deletion is soft, on purpose: an in-flight sync from a device that has been
offline must not resurrect records. Operators purge soft-deleted rows on a
retention schedule; **that schedule is a deployment decision this repository
does not set**, and it must be defined before any real deployment.

The export is asserted by test to contain no password or token material.

## Not claimed

- No security audit or penetration test has been performed.
- No certificate pinning. It is a reasonable next step, and the cost is
  operational (a pin outliving its certificate bricks the app), so it is a
  deployment decision rather than a default.
- No root or tamper detection.
- No end-to-end encryption. The server can read meal records; it must, to
  compute analytics.
- Meal history is not encrypted at rest beyond whatever the database and disk
  provide. That is a deployment concern.
