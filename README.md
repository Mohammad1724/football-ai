# FootballAI

An Android football analysis app that runs its statistical model **entirely on the
phone**. It stores the data it downloads in a local Room database, evaluates team
strength with a Poisson → Dixon-Coles → Elo stack, and produces probability
distributions for every common market (1X2, Over/Under 0.5–3.5, BTTS, exact score,
first-half goals, first-half 1X2, double chance) for both pre-match and live states.

The engine is not a black box and does not pretend to be accurate: every prediction
carries its model version, how many matches it was fitted on, a data-quality score
with the list of missing inputs, and the explanations that moved the numbers. Nothing
is scraped, invented or hard-coded — if a data source does not publish something
(lineups, injuries, odds), the app says so instead of guessing.

## What the app does

| Area | Behaviour |
| --- | --- |
| Fixtures & results | Downloaded from free providers and cached; pull-to-refresh, cached-first rendering, offline banner |
| Pre-match analysis | Independent Poisson → Dixon-Coles (low-score correction) → Elo tilt, with a 1X2 / O-U / BTTS / exact-score distribution and a first-half sub-model |
| Live analysis | A separate time-aware model conditioned on the current score and minute; remaining-goal distributions, next-goal probabilities, and remaining Over/Under lines |
| Model performance | Chronological backtesting (walk-forward, no leakage) with Brier score, log loss, accuracy, calibration bins, ECE and per-competition breakdowns |
| Historical predictions | Every stored prediction can be resolved against the final score, giving Brier / log loss per prediction |
| Data sources | Provider capability matrix, quota counters, API-key storage in the Android Keystore, CSV history import from football-data.co.uk |
| Localisation | English (LTR) and Persian (RTL) with Persian digits, Jalali dates and mirrored layouts |
| Settings | Language, theme, live polling, background sync, storage usage and data clearing |

## Build and install (phone-only workflow)

The project is built by GitHub Actions; you never need a local toolchain.

1. Push to this repository (or open a pull request) on any branch matching `arena/**` or `main`.
2. Open **Actions → Android CI → the latest run**.
3. When the run is green, download the artifact **`football-ai-debug-apk`** and unzip it.
4. Install `app-debug.apk` on the phone (allow "install unknown apps" for your browser/file manager).

The same pipeline runs `:engine:test` and `:app:testDebugUnitTest` before assembling,
so a green run means the tests passed — the log is the only source of truth. If a run
fails, the workflow opens an issue containing the compiler and test excerpt, because
the raw log is awkward to read on a phone.

Pinned toolchain (deliberately not "latest"): AGP 8.5.2, Gradle 8.9, Kotlin 1.9.24,
KSP 1.9.24-1.0.20, Compose compiler 1.5.14, Compose BOM 2024.06.00, Room 2.6.1,
compileSdk/targetSdk 34, minSdk 26, JVM 17.

## Getting data in

On first launch a short setup flow asks for the language, a provider token, the
competitions to follow and the history import; every step can be skipped and repeated
later from **Settings → Run setup guide again**. The same actions are always available in
**Data sources**:

* **football-data.org** — a free personal token is required for every endpoint
  (registration is free and manual). The token is stored in the Android Keystore.
* **API-Football** — optional, free tier is 100 requests/day; requires your own key.
* **football-data.co.uk** — needs no key at all. It supplies the weekly `fixtures.csv`
  (upcoming matches for the main European leagues) and the historical CSV archive; the
  history is what makes the statistical model meaningful, because it needs a few hundred
  finished matches per competition.

The app can therefore show a fixture list with no credentials at all. Authenticated
providers add live scores, statistics, lineups and a wider competition set; when one of
them returns nothing (plan restrictions, exhausted quota), the dashboard falls back to
the keyless source and says so.

## Honest expectations

* Free feeds are **delayed and incomplete**: live scores can lag several minutes, and
  some competitions expose no lineups, injuries or odds at all.
* A football model of this class is well calibrated but only weakly discriminative; the
  realistic ceiling for 1X2 top-pick accuracy is in the low-to-mid 50s percent. The
  app shows calibration and Brier score rather than promising wins.
* No bet is ever implied. `NO_MARKET_EDGE_CLAIMED` is attached to every prediction.

Details: [docs/architecture.md](docs/architecture.md), [docs/model.md](docs/model.md),
[docs/data-sources.md](docs/data-sources.md), [docs/limitations.md](docs/limitations.md).

## Licence and data attribution

Model code is original. Data belongs to the respective providers and is subject to
their terms; the app caches it locally for personal use, and attribution is displayed
on the Data sources screen.

## 0.3.0 correctness safeguards

See [the implementation and verification report (Persian)](docs/fixes-fa-2026-11-10.md).
This version migrates CSV history IDs, blocks post-kickoff pre-match forecasts,
checks live snapshot freshness, and separates regulation-time data from unsupported
extra-time/administrative results. Unknown live time (including an unknown 90+
stoppage-time horizon) pauses predictions instead of guessing. Stored performance
now uses one pre-match forecast per match from the current model version only.

After upgrading from 0.2, re-import API-provider history: old API scores without a
verified regulation-time scope are retained in a quarantined historical source,
not used to train the model. CSV history is migrated automatically. Providers are
not automatically cross-matched. CSV postponements to a different date still need
explicit identity reconciliation; matching by team pair and season alone is unsafe.

Additional checks: `python3 scripts/check_review_sql.py`; device regressions:
`gradle :app:connectedDebugAndroidTest`. These complement the existing CI unit tests.
