# Testing

## What runs, and where

| Suite | Count | Runs in CI | Needs the Android SDK |
|---|---|---|---|
| `ml/tests` | 149 | yes | no |
| `backend/tests` | 181 | yes | no |
| `android/core/model` | 67 | yes | **no** |
| `android/**` unit tests | -- | yes | yes |

330 Python tests and 67 JVM tests run on any machine with Python and a JDK. The
remaining Android unit tests need the SDK and run in the `android` CI job.

```bash
make test            # ml + backend
make verify-domain   # the Kotlin domain module, no Android SDK
make check           # everything that runs without the SDK
cd android && ./gradlew test    # the Android unit tests
```

## What is actually tested

The suites target behaviour that would be expensive to get wrong, not coverage
percentage.

### Estimation correctness

- mass = volume x density, exactly, including round-tripping
- density resolution: exact hit, alias hit, category fallback, refusal
- the fallback confidence is below *every* catalog entry's confidence
- portion geometry: a known region under a known reference produces a known
  volume (0.10 of a frame containing a 26 cm plate at 0.5 -> 233.6 ml)
- absurd geometry is clamped **and** penalised in confidence
- a user correction is more confident than any automatic estimate, but not 1.0

### Chrononutrition

Both implementations assert the same worked example: 08:42, 14:00, 19:16 gives a
634-minute window and an 806-minute fast. A divergence between the app's Kotlin
and the server's Python fails a test rather than reaching a user.

Also pinned:

- a meal at 01:30 belongs to the previous evening
- 04:00 starts a new day
- the same instant falls on different local days in different zones
- a window spanning a DST shift measures **elapsed** time, not wall-clock
- one meal gives `null`, not a zero-length window
- consistency is `null` below two measurable days, not a flattering 1.0

### Uncertainty is never overstated

- the classical engine never emits a "high" confidence
- overall confidence is the product of its three stages, so one weak stage
  dominates
- a featureless frame reports `no_food_detected` rather than inventing a blob
- `estimates_are_approximate` is present on every analysis response

### Security properties

- an unknown email and a wrong password give identical responses
- a refresh token is rejected where an access token is required
- refresh rotation invalidates the presented token
- one user cannot read, delete or correct another user's records (`404`, not
  `403`, so ids do not leak)
- a data export contains no password or token material
- log redaction covers passwords, tokens and email addresses
- a BMP labelled `image/jpeg` is rejected on the sniffed format
- a storage key escaping the storage root is refused
- production settings refuse a missing secret, a short secret, SQLite and debug

### Offline behaviour

- a replayed idempotency key creates no second meal
- a replay does not resurrect a deleted meal
- one bad operation in a batch does not prevent the others applying
- deletions are pulled explicitly
- backoff grows, caps and jitters
- permanent errors do not consume the retry budget

## Approach

**Fakes over mocks.** The repository interfaces are small, so a fake that
records its calls makes a test read as a description of behaviour rather than of
interactions.

**Real database in backend tests.** SQLite with foreign keys explicitly enabled,
created from the ORM metadata. Without the pragma the suite would pass against
constraints production actually enforces.

**Injected clocks and dispatchers.** Every chrononutrition figure depends on the
current instant; a suite that cannot control it can only assert vague things.

**Synthetic images, not fixtures.** The ML suite builds its test scenes from
numpy arrays, so the suite is deterministic and needs no binary assets in
version control.

**Deterministic randomness.** The retry-policy tests drive `Random` at both
extremes, so the growth curve and the cap are asserted exactly rather than
approximately.

## Bugs these tests caught during development

Each of these was a real defect found by a failing test, not a hypothetical:

| Bug | Test that caught it |
|---|---|
| A featureless frame produced a confident food detection | `test_returns_nothing_for_a_featureless_frame` |
| Portion volumes were ~3x too high: the "plate ratio" was the food area, not the plate | pipeline smoke run |
| A clamped estimate still reported 0.59 confidence | `test_absurd_geometry_is_clamped_and_penalised` |
| Meal totals were double-counted: items were both appended and session-added | `test_logs_a_meal_and_derives_mass` |
| A deleted account permanently burned its email address | `test_the_email_can_be_registered_again` |
| `get_settings_dependency` ignored the app's own settings | `test_login_attempts_are_throttled` |
| An 11-digit numeric password was accepted | `test_rejects_weak_passwords` |
| One malformed meal rejected an entire sync batch | `test_one_bad_operation_does_not_lose_the_others` |
| **Every edit to a synced meal was silently discarded** — the replayed idempotency key made the server return the meal unchanged, and both sides reported success | `TestEditsAfterSync` |
| The food catalog cache was never populated, so every food correction fell back to a generic density | dependency audit |
| The profile never loaded on app restart, so a signed-in user saw a blank name | dependency audit |
| Account deletion never reached the server | API-usage audit |

## Not tested

Stated plainly:

- **Recognition accuracy.** No labelled dataset exists, so there is nothing to
  measure against. Tests assert the pipeline's *contract* -- determinism,
  confidence bounds, schema -- not how often it is right.
- **UI rendering.** Compose UI tests are configured (`androidTest`, Hilt test
  runner) but the suite is not written.
- **Load and soak.** No performance baseline exists.
- **Multi-device concurrent sync.** Single-client conflict handling is tested;
  two devices editing the same meal simultaneously is not.
- **The Android sync engine end to end.** Its rules are unit-tested with fakes,
  and the server side of the corrected edit flow is covered by
  `TestEditsAfterSync`, but the two halves have not been exercised together
  against a running backend.
