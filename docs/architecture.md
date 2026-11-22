# Architecture

## Modules

```
:engine   pure Kotlin/JVM — no Android, no network, no database
:app      Android app — presentation, domain glue, data access, sync
```

`:engine` depends on nothing but the Kotlin standard library, so its tests run on a
plain JVM in CI and the whole prediction path can execute offline on the device.

## Layers inside `:app`

```
ui/            Compose screens, navigation, view models, theme, localisation
domain/model/  UI-facing immutable models and enums (MatchSummary, PredictionView, …)
data/
  provider/    provider-agnostic DTOs + the FootballDataProvider contract
  remote/      HTTP clients, one class per provider, DTOs, quota guard, CSV parser
  local/       Room entities, DAOs, database, JSON (de)serialisation, mappers
  repository/  Match, Prediction, Ratings, Performance, Sync, ProviderRegistry
  settings/    DataStore preferences, Keystore credential store, quota counters
  sync/        WorkManager periodic refresh
di/            AppContainer — a hand-written object graph (no DI framework)
core/          Result/Outcome, clock, formatting helpers
```

Dependency direction is strictly downwards: `ui → repository → (remote | local)`,
`ui → domain/model`, and `repository → engine`. The engine never imports anything from
`app`, and no Compose or Android type leaks into the data layer.

### Why a manual container instead of Hilt

The graph is small (one database, one HTTP client, six repositories) and a hand-written
container keeps KSP work low, the CI build fast, and the wiring readable. `AppContainer`
is created once in `FootballAiApplication` and injected into screens through
`LocalAppContainer`, so view models can be constructed per destination without a DI
framework.

## Prediction flow

1. `MatchRepository` loads the fixture and its context (competition, teams) from Room.
2. `RatingsRepository` builds a `RatingBook` for the competition from
   `HistoricalMatchEntity` rows whose kickoff is **strictly before** `asOfEpochMs`.
3. `PredictionRepository` turns that into an engine input and calls
   `MatchPredictionEngine.predictPreMatch(...)` or `predictLive(...)`.
4. The result is mapped to `PredictionEntity` + `PredictionMarketEntity` rows
   (market rows are kept separately so they can be resolved and scored individually).
5. The screen renders `PredictionView`, including quality, explanations and staleness.

`asOfEpochMs` is an explicit parameter everywhere — the same code path is what the
backtester uses, which is why no future information can leak into a prediction.

## Caching, sync and freshness

| Concern | Behaviour |
| --- | --- |
| Cached-first UI | Screens render Room data immediately; the network result replaces it |
| Details TTL | Match details re-fetched at most every 90 s |
| Live polling | 3 min per match while the detail screen is visible and live polling is on |
| Live details | Full detail refresh every 20 min |
| Live snapshot | Persisted at most every 15 min to keep the prediction history readable |
| Background sync | WorkManager every 6 h on any connected network (exponential backoff from 30 min), cancelled when background sync is disabled |
| Staleness | Predictions older than 6 h are flagged `isStale` and re-generated on demand |

Every stored object carries `lastSyncedAt`; the UI shows relative timestamps
("12 minutes ago", Jalali dates in Persian) rather than pretending data is live.

## Error handling and quotas

* `Outcome<T>` (`Success` / `Failure(kind, message)`) is the only result type crossing
  the data boundary; failures carry a typed kind (`NETWORK`, `RATE_LIMIT`,
  `UNAUTHORIZED`, `NOT_FOUND`, `PARSE`, `UNKNOWN`) so the UI can offer the right action.
* `ApiClient` retries idempotent GETs with exponential backoff and jitter (max 3
  attempts), honours `Retry-After`, and never logs secrets.
* `QuotaGuard` enforces per-minute and per-day limits before a request is issued and
  records usage in DataStore keyed by provider + UTC day.
* `ProviderRegistry` exposes capabilities and a health state so the Data sources screen
  can explain *why* something failed instead of showing a spinner forever.

## Storage

Room database `football-ai.db`, version 1, with 11 entities: competitions, teams,
matches, match statistics, match events, lineup players, predictions, prediction
markets, historical matches, standings and sync metadata. Stable string ids
(`provider:league:key`, `provider:team:key`, `provider:match:key`,
`hist:source:competition:home:away:kickoff`) make inserts idempotent, so re-syncing the
same fixture updates rather than duplicates it.

Preferences live in DataStore (language, theme, followed competitions, toggles, quota
counters). API keys are the exception: they are encrypted with an AES/GCM key held in
the Android Keystore and are never written to preferences, logs or the repository.

## Presentation

* Single-activity Compose app, Material 3, dynamic-light/dark themes with a
  high-contrast palette and chart colours exposed through `FootballAiThemeExtra.colors`.
* First-run gate: `FootballAiApp()` watches `AppSettings.onboarded` and shows the
  onboarding flow (language → provider key with live verification → competitions to
  follow → CSV history import) until it is satisfied. `Settings` can re-open it. Nothing
  is mocked to fill the empty start: each step explains what is missing and can be
  skipped.
* `NavigationBar` with four destinations (Home, Matches, Predictions, Performance);
  match, team and competition screens are pushed on top, and Data sources / Settings
  are reachable from the dashboard.
* The 12 requested screens map onto 9 composables: Pre-match and Live analysis are tabs
  of Match Details (a match cannot be pre-match and live at once), and Historical
  Predictions is the "resolved" filter of the Predictions screen.
* RTL is handled by Compose's automatic mirroring plus explicit numeral and date
  formatting (`Formatters`), so Persian never shows Latin digits or Gregorian dates.
* Every list screen implements loading, empty, error and offline states.

## Testing

* `:engine` — Poisson/matrix/markets, Dixon-Coles fitting, Elo, live updates,
  normalisation, quality scoring, metrics and a synthetic-league backtest.
* `:app` — CSV history parsing (quoted fields, missing odds, bad dates), formatters
  (digits, Persian separators, relative buckets), id generation, status parsing,
  provider-to-entity mapping and JSON round trips for stored structures.

Tests are never weakened to make CI green; failures are fixed in the production code
instead.
