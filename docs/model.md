# The prediction model

Everything here lives in `:engine` and is a pure function of its inputs: given the same
`PreMatchInput` / `LiveInput` and the same `asOfEpochMs`, the output is identical. That
is what makes the backtest trustworthy and the app reproducible offline.

## 1. Expected goals (the Poisson core)

Each team gets an expected-goals rate built from a league baseline multiplied by two
strength factors:

```
λ_home = baselineHome × attack_home × defence_away × homeAdvantage
λ_away = baselineAway × attack_away × defence_home
```

The baselines are the competition's own historical averages (`LeagueBaseline`); the app
never borrows another league's scoring level. Strength factors are estimated with an
exponentially weighted record (half-life 240 days), so recent form matters more than
matches from two seasons ago.

Both rates are clamped to `[0.08, 6.0]` and only matches from the last 1200 days are
considered, so a small or unrepresentative sample cannot produce absurd values.

## 2. Dixon-Coles correction

Independent Poisson under-predicts 0-0, 1-0 and 1-1 while over-predicting 1-0/0-1
asymmetries. `DixonColes.fit(...)` estimates the low-score dependency parameter `ρ`
(and time-decayed attack/defence terms) by gradient-based maximum likelihood with a
small L2 penalty and a hard bound of `ρ ∈ [-0.20, 0.20]`,
then applies the standard `τ(x, y)` correction to the joint matrix.

* `ρ = 0` degrades gracefully to the plain Poisson model and the descriptor reports
  "Independent Poisson" instead of pretending otherwise.
* Fitting is skipped (and reported as skipped) when a competition has fewer than 40
  usable matches inside the window — a `ρ` estimated from a dozen matches is noise.

## 3. Elo tilt

An Elo difference (K = 20, home advantage ≈ 60 points, 1500 = league average) is
converted into a multiplicative tilt on the two λ values. The tilt is capped at ±25%
and applied symmetrically, so Elo redistributes expected goals between the teams
without changing the total. Its weight (default 0.15) is recorded on every prediction.

Progression matters: Poisson is a scoring model that knows nothing about who is
better; Dixon-Coles adds low-score realism; Elo adds a result-based strength signal
that reacts faster than a fitted attack/defence ratio. Each layer can be inspected in
isolation because each is a separate class.

## 4. Markets

From the (bounded, normalised) score matrix up to 10 goals per side:

| Market | Derivation |
| --- | --- |
| 1X2 | sums of the lower / diagonal / upper triangle |
| Double chance | pairwise sums of the 1X2 probabilities |
| Over/Under 0.5 … 3.5 | cumulative totals with the 0.5 offset (push never occurs) |
| BTTS | 1 − P(home = 0) − P(away = 0) + P(0-0) |
| Exact score | matrix cells, top 6 shown, full matrix retained |
| First-half goals / 1X2 / BTTS | separate first-half rates (≈ 44% of full-time λ, Dixon-Coles applied to the reduced matrix) |

Probabilities are renormalised after every correction, so 1X2 always sums to 1 within
floating-point tolerance. There is a test for exactly that.

## 5. The live model is not the pre-match model

`predictLive(...)` is a different calculation, not an adjustment of the old one:

1. Remaining expected goals are scaled by remaining minutes (with stoppage-time
   allowance), using the pre-match rates as the intensity.
2. Live statistics (shots, shots on target, possession, dangerous attacks) shift the
   remaining intensity by at most ±20%, and red cards modify the two sides
   multiplicatively (0.88 for the team a man down, 1.12 for the team a man up).
3. The current score is treated as fixed; only the remaining goals are modelled, so
   "1-1 at minute 70" produces ~20 minutes of goal expectation, not a full match.
4. Outputs: remaining-goal distribution, next-goal probabilities (home / away / none),
   full-time 1X2 conditional on the current score, remaining Over/Under lines, and the
   list of adjustments actually applied.

Without live statistics the model still works from the score and the clock — it just
reports the missing inputs in the quality block.

## 6. Data quality

`DataQualityAssessor` combines weighted signals — historical depth for the competition
(0.45), per-team sample size (0.20), lineups (0.10), injuries (0.10), odds (0.05), live
statistics (0.10) — into a completeness score, a level (`HIGH`, `MEDIUM`, `LOW`,
`INSUFFICIENT`), the list of missing items, and notes such as `ODDS_UNAVAILABLE` or
`MARKET_DISAGREES`. A prediction with `INSUFFICIENT` quality is displayed with a warning
instead of being silently trusted.

## 7. Evaluation

* **Brier score** and **log loss** for 1X2, Over 2.5 and BTTS.
* **Accuracy** for the top 1X2 pick, compared against the base rate so an apparently
  decent number can be judged against "always pick the home team".
* **Calibration**: 10 fixed bins with a reliability curve, expected calibration error,
  and the per-bin counts, so a confident model that is badly calibrated is visible.
* **Chronological backtesting** (`Backtester`): matches are processed in kickoff order,
  ratings are refit every 150 matches, and each prediction only sees matches that
  kicked off earlier. There is no shuffling anywhere in the pipeline.
* Per-prediction scoring: resolving a stored prediction stores its Brier and log loss,
  which is what the Historical predictions screen shows.

### Realistic expectations

Football is noisy. This class of model typically lands **1X2 top-pick accuracy around
50–55%** against a ~44% home-win base rate, and beats the base rate on Brier score
rather than on hit rate. Over/Under and BTTS probabilities are usually better
calibrated than 1X2 picks. Exact score is inherently low-probability — the top
scoreline is often under 15%. **None of this is a betting edge**, and the engine
attaches `NO_MARKET_EDGE_CLAIMED` to every output. Where the model disagrees with
bookmaker odds (when odds exist and the user has them), the app labels it as market
disagreement, never as value.

## 8. Model versioning

`ModelDescriptor` (name, version, whether Dixon-Coles was used, `ρ`, fitted match count,
Elo weight) is stored with every prediction. When the engine's constants change, the
version string changes, so an old stored prediction is never mixed up with a new one in
the performance figures.
