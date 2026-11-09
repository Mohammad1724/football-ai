# Limitations and known gaps

This file exists so the app's claims can be checked. It is updated as the project
moves; nothing here is aspirational wording — each item is a real, current limitation.

## Data

1. **Every usable fixture feed needs a free API token.** football-data.org requires
   `X-Auth-Token` on all endpoints; API-Football requires a key. football-data.co.uk
   CSV history is the only keyless source, and it contains finished matches only — no
   upcoming fixtures. Without a token the app can train and evaluate a model but cannot
   show a match list.
2. **Free tiers are thin.** football-data.org: 10 requests/minute and 12 competitions.
   API-Football: 100 requests/day. Both mean the app must cache aggressively and refuse
   work rather than silently burn the quota; that is what the quota counters are for.
3. **Live data is delayed on the free plan.** football-data.org live scores lag. When
   only that provider is configured, the app marks live analysis as lower quality rather
   than presenting delayed scores as real-time.
4. **No lineups, injuries or odds from free fixture feeds.** These appear only if the
   user configures API-Football (partial) — otherwise they are listed as missing inputs
   and lower the data-quality score. odds exist in the football-data.co.uk CSV archive
   for historical matches only (used for backtest comparison, not for a "value bet"
   claim).
5. **Persian team/competition names are not translated.** The providers publish names
   in English; the UI strings are Persian but data labels remain as delivered. Inventing
   translations would risk showing the wrong club.
6. **Competitions outside the supported set are shown, not guessed.** A competition
   whose historical CSV does not exist is flagged `INSUFFICIENT` quality and gets
   league-baseline predictions with an explicit warning.

## Model

7. **Calibration is measured, not applied.** The app computes reliability bins, ECE,
   Brier and log loss, and the Elo blend acts as a shrinkage term, but no fitted
   isotonic/Platt correction is applied to live output yet. Doing it well requires
   several hundred resolved predictions per competition; until then the honest move is
   to report calibration rather than fit a curve on noise.
8. **First-half model is a proportional split** (`firstHalfShare = 0.44`) with a
   Dixon-Coles correction, not a separately fitted first-half goal model. It cannot
   capture half-specific effects such as injury-time goals in the first half.
9. **No player-level or lineup-driven modelling.** Without reliable lineup data, the
   engine uses team strength only. An injured striker is invisible unless the historical
   results already reflect it.
10. **Live intensity adjustment is coarse** (shots ratio, ±20% cap, red cards 0.88/1.12).
    It is deliberately conservative; it cannot model tactical changes or momentum.
11. **Exact-score probabilities are small by nature.** The modal scoreline is often below
    15%. The UI shows the top scores with their probabilities instead of implying a
    prediction.
12. **No betting edge is claimed anywhere.** The engine appends `NO_MARKET_EDGE_CLAIMED`
    and the UI never frames a probability difference against odds as value.

## Database schema

The specification listed 14 entities. Eleven are implemented. The three that are folded
into other structures are:

| Specified | Current representation | Why |
| --- | --- | --- |
| `Season` | `MatchEntity.season` + competition-season strings on history rows | a season has no behaviour of its own; normalising it would add a join without changing any query |
| `Player` | `LineupPlayerEntity` (name, number, position per match) | only used to render lineups; no player-level statistics exist in the free sources |
| `TeamForm` | derived on demand from `HistoricalMatchEntity` | storing a rolling aggregate would go stale and duplicate what the rating book computes |
| `Injury` | none | no free source publishes injuries; an empty table would be a lie |
| `ModelVersion` | `ModelDescriptor` fields stored on every prediction (`modelName`, `modelVersion`, `fittedMatches`, `rho`) | versioning requirement met without a table that would always hold a single row |
| `DataSource` | `ProviderInfo` + `ProviderRegistry`, quota in DataStore | provider metadata is code, not user data; only quota counters are mutable |

If a future source supplies injuries or player statistics, the missing tables should be
added with a proper Room migration rather than by reusing existing columns.

## Product/UX

13. **No widgets, notifications or Android Auto integration.** Background work is a
    single periodic sync that only refreshes followed competitions.
14. **Charts are drawn with Compose primitives**, not a charting library: deliberate, to
    keep the APK small and the build dependency-light, but it limits interactivity.
15. **Tablet/foldable layouts are functional but not optimised** — the app targets a
    phone screen, which matches the intended device.
16. **Offline mode covers everything already downloaded**; a cold install shows the
    setup flow, which works without a network until the key check, and the resulting
    screens fall back to explicit "no data yet" states.

## Verification

17. **No APK is distributed by this repository's releases.** The debug APK is produced as
    a CI artifact on each green run; installing it requires downloading the artifact and
    allowing installs from unknown sources.
18. **The app has not been run on a physical device from this environment.** CI proves
    that the code compiles, that all unit tests pass and that the APK assembles; it does
    not prove runtime behaviour on a Galaxy A55. Any runtime report from the user is
    treated as the source of truth.
