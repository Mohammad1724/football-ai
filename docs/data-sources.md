# Data sources

The app talks to providers only through the `FootballDataProvider` contract
(`info`, `competitions()`, `matches(...)`, `match(...)`, `standings(...)`, …), so a new
source is a new class plus a registration in `ProviderRegistry`. Nothing in the UI knows
which provider produced a fixture.

## Supported sources

### 1. football-data.org (v4) — primary fixture source

* Free tier: 12 competitions, **10 requests/minute**, delayed live scores.
* No lineups, no injuries, no odds, limited statistics → the app flags those gaps
  (`LINEUPS`, `INJURIES`, `BOOKMAKER_ODDS`) instead of inventing them.
* Requires an `X-Auth-Token`. Registration is free but manual, so the app shows a clear
  `MISSING_API_KEY` state (with a link to the sign-up page) instead of failing silently.
* Best used as: fixtures, results, standings.

### 2. API-Football (v3) — optional live detail source

* Free tier: **100 requests/day**, 10/minute, quota resets at 00:00 UTC.
* Gives live minute, events, statistics (shots, possession, cards) and lineups for many
  competitions — the inputs the live model prefers.
* Requires the user's own key (`x-apisports-key`). The free quota is small, so the app
  counts every call, refuses to exceed the daily limit, and warns before enabling live
  polling for many matches at once.

### 3. football-data.co.uk (CSV archive) — history and calibration

* Free, no key, plain `https://www.football-data.co.uk/mmz4281/{YYyy}/{code}.csv`.
* Contains results, half-time scores, shots and 1X2 / Over 2.5 / Asian-handicap odds for
  many leagues, updated a few days after each matchday.
* This is the backbone of the model: 2–3 seasons per competition (~700–1100 matches)
  give a usable Dixon-Coles fit. Default selection: `E0, SP1, D1, I1, F1`.
* Files are parsed defensively (quoted fields, missing odds, absent columns) and rows
  without a final score are skipped.

## What each source cannot do

| Need | football-data.org | API-Football (free) | football-data.co.uk |
| --- | --- | --- | --- |
| Fixtures / results | yes | yes | delayed (days) |
| Live minute | delayed | yes | no |
| Lineups | no | mostly | no |
| Injuries | no | partial | no |
| Match statistics | no | yes | shots only |
| Odds | no | not on free tier | yes (closing) |
| History depth | current season | limited | many seasons |

No free source offers all of it. That is a hard constraint, and it is exactly why the
prediction screens carry a data-quality score.

## Rate limiting, retries, quotas

* `QuotaGuard` holds a per-minute and per-day budget per provider. Counters live in
  DataStore keyed by UTC day and are visible on the Data sources screen.
* `ApiClient` refuses requests that would exceed the budget, retries `5xx` / IO errors
  with exponential backoff + jitter (3 attempts), honors `Retry-After` on `429`, and
  converts everything into `Outcome.Failure(kind, message)`.
* Sync is incremental: fixtures are fetched per followed competition and upserted by
  stable ids, so a repeated sync is cheap and never duplicates rows.
* History import fetches one CSV per competition per season, sequentially, and stops
  early if the provider starts failing.

## Secrets

* API keys are entered in the app, encrypted with an AES/GCM key from the Android
  Keystore, and stored under a private preferences file. They are never written to the
  repository, to logs, to the Room database or to exported backups.
* The app requests **no key at all** for football-data.co.uk history, so the model can
  be trained without any credential; fixture lists still need one free token from
  football-data.org or API-Football.
* Because the app calls providers directly from the device with the user's own key,
  there is no backend that could leak or proxy a key. The trade-off is that every user
  needs their own free key for the optional providers — which is also what keeps the
  project free to run. A proxy backend (e.g. a free-tier Cloudflare Worker holding one
  shared key) would improve convenience but adds a permanent server that the project
  requirements explicitly rule out for normal operation.

## Adding a provider

1. Add a class implementing `FootballDataProvider` in `data/remote/`.
2. Declare its `ProviderInfo` honestly: capability flags, quota numbers, and whether it
   needs a key.
3. Map DTOs to the provider-agnostic `Remote*` models in `data/provider/RemoteModels.kt`.
4. Register it in `ProviderRegistry`.
5. The sync, quality, live and prediction paths pick it up automatically — no UI change.
