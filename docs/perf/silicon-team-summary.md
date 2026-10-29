# Silicon fork: results and first upstream proposal

28 October 2026, updated 29 October. The `jesyspa/silicon` fork reduces avoidable JVM work, bounds verifier-pool startup by the program's parallelism, and recovers from Z3 memory-outs. Propose the store-lookup fix first: it is a small local change that preserves solver input and applies independently to upstream head. The fresh-prover retry has a known regression and is not a candidate for that proposal.

## Measurements

Time ratios are candidate/control; below 1 is faster. The evening and client comparisons use upstream as control; isolated changes use their stated parent build. Intervals are 95% confidence intervals, with the method specified for each experiment. These are workload-specific results, not a universal speedup.

Current fork, 29 October daytime run (3,351 requests; same corpus, lanes, caps and priming as below, with eight seeds for files profiled below 10 s and four otherwise): U = upstream head `ff386aec`, F = fork `45397fd7` with fresh-prover retry off (its default), P = fork `f929b360` from 27 October with retry on. Both fork arms also enable the opt-in `silicon.exp.seqDistinctInverse` and `silicon.exp.exactReborrow` switches, which the plain jar leaves off.

| Time ratio | F/U | P/U | Stable files |
|---|---|---|---:|
| Wall, all eligible files | 0.801 [0.769, 0.830] | 0.816 [0.788, 0.844] | 178 / 177 |
| Total process CPU | 0.832 [0.787, 0.875] | 0.840 [0.799, 0.882] | 128 / 127 |

Turning the retry off did not cost speed. U crashes on 53 requests; neither fork arm crashes. Per-file medians across seeds, unstable files (max/min > 2) excluded, percentile bootstrap over files. Analysis: `~/data/viper-corpus-results/nightly/2026-10-29/analysis.txt`.

Evening comparison, completed at about 22:40 CET (all 1,500 requests): U = upstream head `ff386aec`, F = fork `46695469` with its then-default fresh-prover retry, S = U plus only store lookup `980151d5`.

| Time ratio | F/U | S/U | Stable files |
|---|---|---|---:|
| Wall, all eligible files | 0.806 [0.773, 0.839] | 0.966 [0.938, 0.993] | 206 |
| Wall, upstream median ≥5 s | 0.748 [0.680, 0.818] | 0.938 [0.897, 0.974] | 61 |
| Total process CPU | 0.846 [0.805, 0.888] | 0.986 [0.952, 1.022] | 186 |

Method: 250 stratified corpus files, two paired Z3 seeds per file, warm JDK 21.0.10 / Z3 4.16.0, one verifier, five two-CPU lanes, 4 GiB heap, 3 GB Z3 memory limit, 7 GiB lane limits, 300 s outer timeout and profile-derived caps (8×, minimum 60 s). Tasks are shuffled; inputs profiled below 10 s are primed once per runner. Take each file's median across seeds, then the equal-file-weight geometric mean of candidate/U ratios; 10,000 percentile-bootstrap resamples over files. Require both seeds, identical completed verified/failed outcomes, positive timing and max/min ≤2 in every measured arm. For wall, 16 unstable files and 28 outcome-changing/non-completing files are excluded from each comparison; no timeout or crash is counted as fast verification. Intervals describe variation over the retained files, not all sources of measurement error. This is a shared desktop, with only two seeds and no independent A/A arm; small effects and small groups need replication.

Per-group wall ratios (same method; unadjusted intervals, no multiple-comparison correction):

| Corpus group | F/U [95% CI] | S/U [95% CI] | n |
|---|---|---|---:|
| Dafny via Gobra (DaG) | 0.825 [0.771, 0.885] | 1.076 [1.029, 1.128] | 7 |
| Dafny Viper (DaV) | 0.837 [0.634, 1.067] | 0.747 [0.569, 0.964] | 11 |
| Flow graphs (Flo) | 0.855 [0.760, 0.980] | 0.959 [0.886, 1.028] | 9 |
| Gobra tests (Go) | 0.741 [0.617, 0.897] | 1.027 [0.869, 1.205] | 10 |
| Gobra clients (GoC) | 0.814 [0.738, 0.904] | 1.017 [0.979, 1.070] | 13 |
| Product programs (PP) | 0.646 [0.581, 0.726] | 0.961 [0.912, 1.032] | 14 |
| Nagini (Py) | 0.904 [0.840, 0.971] | 1.000 [0.881, 1.095] | 11 |
| Nagini information flow (PyP) | 0.673 [0.594, 0.762] | 0.955 [0.890, 1.021] | 14 |
| RSL | 0.953 [0.882, 1.020] | 0.992 [0.898, 1.079] | 12 |
| Reachability (Rea) | 1.128 [1.053, 1.209] | 1.106 [1.063, 1.156] | 5 |
| Prusti tests (Ru1) | 0.796 [0.670, 0.949] | 1.021 [0.923, 1.116] | 14 |
| Prusti programs (Ru2) | 0.608 [0.419, 0.858] | 0.860 [0.681, 1.057] | 11 |
| Prusti/Mendel (Ru3) | 0.924 [0.852, 1.005] | 0.973 [0.914, 1.035] | 14 |
| 2vyper (SC) | 0.793 [0.691, 0.877] | 1.030 [0.913, 1.174] | 8 |
| 2vyper resources (SCR) | 0.865 [0.809, 0.927] | 0.967 [0.921, 1.019] | 13 |
| VerCors publications (VeC) | 0.761 [0.584, 0.958] | 0.872 [0.762, 0.986] | 8 |
| VerCors VerifyThis (VeV) | 0.821 [0.782, 0.891] | 0.953 [0.872, 1.041] | 4 |
| Viper examples (Vi) | 0.724 [0.637, 0.822] | 0.983 [0.916, 1.054] | 14 |
| VOILA, typecheck only (Vo) | 0.971 [0.871, 1.084] | 0.980 [0.895, 1.084] | 14 |

F uses about 19% less wall time on retained files. The S result supports a modest store-lookup gain, not a universal improvement: Dafny/Gobra and reachability groups are slower here. A 29 October replication ([ideas.md §17](ideas.md)) does not reproduce those losses beyond JVM variation on a second host (DaG 1.012 [0.984, 1.040], Rea 1.008 [0.977, 1.040]), finds no hot store lookup in JFR profiles and identical solver logs in all 448 comparisons; a 4–5% DaG slowdown remains on this desktop with no identified mechanism. That qualifies, rather than overturns, the first-proposal recommendation. S and U have identical outcome classifications on all 500 paired requests, including 16 crashes each; F has zero crashes. F verifies 251 requests versus U's 247, but also has 31 timeouts versus U's 29. These are not claims of uniform completeness improvement.

Independent client measurements provide context:

- SnaKt: summed JUnit time 0.226 [0.190, 0.269], about 4.4× faster; full Gradle wall 0.262 [0.227, 0.303]. Four alternating upstream/fork pairs, fresh test JVM per run, many verification calls per JVM; Student-t intervals over paired log ratios. This compares SnaKt's pinned April upstream artifact `b005bebd` with fork `ae1d99dc`, including different Silver versions, not tonight's upstream head. A variable `z_function` test amplifies the total: excluding it gives 0.352 [0.233, 0.531]. Shared physical cores, fixed upstream-first ordering and only four pairs limit attribution.
- Prusti: 0.721 [0.680, 0.763] over 88 tests taking at least 1 s in all four runs (two per artifact); all 376 tests have matching outcomes. Ratio of per-test mean times, geometric mean over tests, percentile bootstrap over tests. This older comparison predates the startup changes.
- Silicon suite: the older 271-test timing subset gives 0.805 [0.765, 0.846] in one fork run but 0.943 [0.892, 0.997] in the second, against the same upstream run. Bootstrap over tests does not capture that run-to-run variation. Do not present 20% as a repeatable suite-wide gain. The selected tests take at least 0.2 s upstream; the distinctness outlier below is excluded.

## Changes and evidence

Sizes are source insertions/deletions, excluding tests and tooling; multi-commit rows sum patch churn, not the minimal review diff. Effects come from different experiments and must not be added or multiplied.

| Change | Size (+/−) | Measured effect | Changes results? |
|---|---:|---|---|
| Single-variable store lookup (`980151d5`) | 2/5; with tests 51/5 | Earlier isolated 250-file, 3-seed run: wall 0.951 [0.909, 0.993], n=155; files ≥5 s 0.892 [0.822, 0.953], n=59 | No stable outcome changes; solver input preserved |
| Recover from Z3 memory-out (`d5926d69`, `359a230e`) | 197/33 | Robustness rather than speed: restart/replay instead of aborting verification | Yes: memory-out becomes incompleteness/continued verification instead of a crash |
| Cap workers per program; concurrent lifecycle and preamble (`051150d8`, `5594c850`, `76810ca5`) | 202/55 | Warm one-method file 120–190 →19 ms; 16-method file 83→41 ms; exploratory 16-core suite 520→about 250 s | Matching outcomes in measured controls; command order per prover retained |
| Skip sharing analysis for provably small terms (`ae1d99dc`) | 46/1 | 40 random files, 4 paired seeds: wall 0.971 [0.932, 1.002]; bam_load 25.3→14.3 s | Identical normalized Z3 logs and outcomes in 344 requests |
| Direct SMT rendering and large-term `let` sharing (`65abfed7`, `ef9007f7`) | 546/332 | Reborrow example: 300 s timeout→14–18 s; broad final-threshold run neutral on 250 files | Changes solver input/search; no outcome changes in that final 250-file run |
| Exact permission take in reborrows, opt-in (`7e42b83e`, `f807f229`) | 27/22 | Prusti-heavy sample wall 0.969 [0.953, 0.985]; small effect subject to runner-position bias | All 1,612 paired outcomes match; changes permission terms |
| Whole-sequence distinctness via a fresh inverse function (`8bc5fd04`…`f0ac311e`) | 117/14 | linked-list-qp-append median 139→1.1 s over 10 seeds; corpus-neutral | Equisatisfiable encoding, but different solver search; suite controls match |
| Split evaluator/traversal for JIT (`7f20923e`) | 28/5 | No established broad speed gain | Refactoring; suite controls match |
| Silver macro-list builder and reserved-name set reuse (`bda9a9da`, Silver) | about 17 changed lines | Allocation ratios 0.980 on bam, 0.916 on refcell, 0.966 on Nagini; no latency gain established | Frontend differential controls match |
| Contradiction-check skip plus fresh-prover retry (`9a959ab9`…`01a6527f`) | 150/28 | Earlier form wall 0.957 [0.923, 0.991]; not a measurement of the final retry policy | Yes; known negative-test regression, see below. Do not propose retry |

## Correctness and limitations

Matching test results are evidence, not a proof of soundness. The older upstream/fork comparison had matching outcomes on 376 Prusti tests and 1,643 common Silicon tests. The sharing-precheck gate passed 1,574 Silicon tests; startup/preamble changes have command-order and original-exception regression guards. SnaKt gives 141 passed / 1 failed in every run: its existential-postcondition expected error is absent on both pinned upstream and fork under Z3 4.16, so that failure is not fork-specific.

There is a known fork regression outside those suites: fresh-prover retry changes `scanSkipLast` in jormungandr's `arraydeque_bfs_negative` from a quick expected failure to a 300 s timeout under Z3 4.16. Retry is opt-in on main since `0d6c7e5f`; the 28 October evening F artifact still enables it, the 29 October F arm does not. Contradiction-check skipping remains enabled. The revised default passes 1,578 Silicon tests (0 failed, 62 canceled, 54 ignored), and disabling retry restores the expected jormungandr diagnostic. Do not equate tonight's F timings with the revised default build, or claim all client outcomes are unchanged. Separately, upstream's `model.partial=true` change (#940) omits unconstrained variables from counterexamples and changed one SnaKt-derived golden; distinguish model presentation from a verification verdict.

## What to propose first

1. Store lookup (`980151d5`): replace whole-map projection on every `get`/`getExp` with a single underlying-map lookup, leaving public projections unchanged. The production patch is seven changed lines in `src/main/scala/state/Store.scala`, with 49 added test lines covering immutable updates, right-biased unions, missing keys, absent expressions and equivalent AST keys. It applies cleanly to upstream `ff386aec`. It has no solver-policy or encoding change and is easy to review independently.
2. Z3 memory-out recovery: a separate bug-fix proposal with a crashing reproducer and explicit incompleteness semantics. Upstream crashes on memory-limited VerCors examples; recovery is not a claim that those programs verify.
3. Verifier-pool startup: relevant to ViperServer and clients making many small calls. Its larger concurrency/lifecycle surface deserves a separate review and client-level tests; SnaKt's aggregate gain does not isolate this change.

## Build and try

A prebuilt `silicon.jar` of the 29 October F arm is attached to the `fork-2026-10-29` release on https://github.com/jesyspa/silicon/releases; run it with JDK 21 and Z3 4.16.0 as `java -jar silicon.jar --z3Exe "$(command -v z3)" example.vpr`. To build instead, with that Z3 on PATH:

```sh
git clone --recursive --branch main https://github.com/jesyspa/silicon.git
cd silicon
export Z3_EXE="$(command -v z3)"
sbt assembly
java -jar target/scala-2.13/silicon.jar example.vpr
```

Record the checked-out commit and Z3 version when comparing. For the smallest independent trial, apply only `980151d5` to upstream `ff386aec` and rebuild; this is the 28 October S arm. No contact with viperproject is needed to reproduce these local comparisons.

Evidence: [performance ledger](ideas.md), especially §§5.1–5.2, 14 and 15. Raw local artifacts: `~/data/viper-corpus-results/evening-20261028/` (`analysis.json`/`.txt` for the run; `summary-analysis.json`/`.txt` for groups, ≥5 s and CPU breakdowns; regenerate the latter with `python3 analyze-summary.py` in that directory), `snakt-20261028/REPORT.md`, `nightly/2026-10-27/analysis.txt`; older client/impact analyses under `impact-20261027/` and `suites-20261027/`. The older impact corpus result, 0.780 [0.751, 0.809], and nightly result, 0.813 [0.784, 0.842], used different fork snapshots and inclusion rules; neither substitutes for the isolated S comparison.
