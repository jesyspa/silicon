# Silicon performance ideas ledger (2026-10-14 to 2026-10-27)

Purpose: one place listing every Silicon performance idea tried or proposed in this window, with its result, so work is not repeated.
Built from wisp transcripts, workflow-run artifacts, `~/data/viper-corpus-results/` reports and git. Most transcripts were read through final reports and targeted greps, not every step. Numbers are quoted from those sources; "derived" marks arithmetic done while writing this file.

## Answer to "what was rebuilt repeatedly instead of cached"

The most direct match is the **Store projection** (5.1): `MapBackedStore.get/getExp` rebuilt the whole term/expression map on every single-variable lookup (refcell: 12,928 lookups visiting 529,197 projected entries). Commit 302e29e2 (in integrated 48a8f45b, not on main) removes it; bam_load median wall 64.65 -> 33.05 s and 38.74 -> 17.70 s (isolated, 3 repeats). Not landed pending broader controls.
Related term-graph cases: (a) the SMT renderer expanded the term DAG as a tree (`perm - min(perm,x)`: 315,569 occurrences, 75 distinct); fixed by let-sharing, landed (2.1); (b) the sharing analysis itself was rebuilt for every conversion even when thresholds rejected it; size precheck e544f5f9 (5.2, combined-only effect); (c) function-precondition traversal memo c01c78a3 (4.3, no representative gain); (d) path-condition membership/projection (4.1, 4.5); (e) `PathConditions.declarations` (4.4, unmeasured). The only caching idea that was a true cross-call cache, the proof cache (2.11), was slower on Uniswap and rejected.

## How to read this

- **main** = `origin/main` at 359a230e. **integrated** = commit is an ancestor of 48a8f45b (branch `integration/tonight-guarded-20261023`, 16 commits past 359a230e, not on main). Ancestry was checked with `git merge-base --is-ancestor`.
- **isolated** = candidate measured against its own baseline with nothing else changed. **combined** = measured only inside a stacked build.
- Corpus: `~/data/viper-corpus` (4,858 files; 4,009 after dropping duplicates and `_preambles`). "quick" = 250-file stratified subset. "capped full" = 1,228 files (all non-Ru2 plus 500 sampled Ru2). Baseline = 572cf2c8 unless stated.
- Z3: pinned version is 4.16.0. Several early measurements (the 5-arm distribution run, early D/E verdicts, the "5% Silicon CPU" figure) used Z3 4.8.7 on PATH. Only the 2026-10-23 night run was warm JDK 21 / Z3 4.16.
- Noise: pinned control-vs-control log SD 0.058 (about 6%); drift 1.1%. Many figures below are single pairs or 3-request medians.
  - Update 2026-10-27: that figure is same-seed noise. Across Z3 seeds the median per-file |log ratio| is 0.17 on wall (files ≥ 1 s), and 14 seed-fragile files carry 45-78% of the old repeat variance (14.8). Sections 1-13 are single-seed; section 14 is seeded and paired and supersedes them where they disagree.
- Section 14 records the 2026-10-25 to 10-27 seeded run; origin/main is now 7e42b83e.
- Short ids: `miles`, `amadeo`, `yarrow`, `seivarden`, ... are wisp personas (`kameliya/<name>@<created>`); `sil-<name>-N` are their workers.

## 1. Landed on origin/main, with measured effect

Only the first three are Silicon source changes that affect speed. None has a clean, replicated end-to-end win on Z3 4.16.
Update 2026-10-27: E (as V3), the sequence-distinctness rewrite (flag-off) and the reborrow prototype (flag-off) have since landed; see 14.1 for the table and the measured effect of main against 572cf2c8.

| Idea | Commit | Best measured effect | Isolated? |
|---|---|---|---|
| Split evaluator/term-traversal dispatch | 7f20923e | disputed: +7% median (some files) vs "within noise" | yes, conflicting |
| SMT direct rendering + let-sharing for huge terms | 65abfed7, ef9007f7 | reborrows: 300 s timeout -> 14-18 s; otherwise equal on 250 files | yes, but rendering vs sharing not separated |
| Warm-JVM harness, per-lane memory scopes | bdc72b50, a757b1a8 | tooling | n/a |
| Z3 memory-out recovery | d5926d69, 359a230e | robustness; recording overhead within noise | n/a |
| sbt 1.10.11 bump | 61c3a132 | build only | n/a |
| Harness / benchmarking | 22770889, 572cf2c8, ad5848df | tooling | n/a |

Combined effect of everything on main vs the original 572cf2c8, completed paths of the night run (amadeo-11 analysis of the 10-23 night run): wall ratio M/O 0.927 and 0.916 (two repeats, n=223). Not decomposed per commit; part of it is that the original crashes on Z3 memory-outs.

## 2. SMT encoding and term rendering

### 2.1 SMT direct rendering + `let` sharing (landed)
- What: `TermToSMTLib2Converter` writes to a StringBuilder instead of a width-80 pretty-printer document; repeated subterms of very large terms outside binders are bound with `let`.
- Commits: 65abfed7 (first, thresholds 1000 nodes / ratio 2), ef9007f7 (final: share only terms with >=10,000 tree nodes that shrink >=10x; conversions without sharing no longer write converter fields, so global converters do not race). Branch `perf/smt-render-sharing`. Both on main.
- Diagnosis behind it (profiling B, `profiling-20261021/B/report.md`): `reborrows` (Prusti, Ru2/new/viper_programs/031) emitted 1,332,694,002 bytes of SMT; largest assertion 45,685,931 bytes held 315,569 S-expression occurrences but only 75 distinct subexpressions (`perm - min(perm,x)` chains from ChunkSupporter.scala:179-190, StateConsolidator). Replay of the prefix: 37.25 s total, 0.314 s of it check-sat; original wall 316.91 s.
- Effect:
  - reborrows: baseline 300 s timeout at 1.33 GB SMT -> verifies in 14-18 s, 1.08 MB (miles turn 29 step 79; sil-miles-3 step 1). This is a timeout bound, not two completed runs; hana noted ">16x" is not a measured ratio.
  - Paired wall (miles step 79): nested_struct 48 -> 13 s; selection_sort 76 -> 38 s; getInterfaceState 238 -> 206 s; poker 84 -> 72 s.
  - 109-file sweep, checkTimeout 2, 120 s timeout (sil-miles-3 step 123): wall 3352.3 -> 3124.8 s, Z3 CPU 1375.7 -> 1230.4 s, total CPU 3312.4 -> 3088.4 s (derived: -6.8% wall, -10.6% Z3, -4.1% total). Lets used only in reborrows, nested_struct, selection_sort.
  - 250 files, default budgets: wall 11240 vs 11234 s (off), Z3 7218 vs 7218 s: equal, 0 outcome changes.
  - Earlier claim "about 5% less Silicon CPU, no Z3 change" (220 finished files, fresh JVM per file, Z3 4.8.7); later attributed mainly to direct rendering rather than sharing (miles). Not re-measured on 4.16.
  - 5-arm cold distribution (`corpus-stats-20261022/report-v2.md`, single sample): ef9007f7 vs 7f20923e wall ratio mean 0.974 / median 0.970 / geomean 0.9636, solver CPU geomean 0.981, Silicon CPU geomean 0.968.
- Correctness: threshold 1000 nodes / ratio 2 caused six outcome changes (043-pairs-sum-to-zero verified->timeout 35->120 s; 118-get_closest_vowel verified->timeout 47->120 s; SerializeTo V->F). Lets perturb Z3 search, hence the raised thresholds. Final: 0 changes on 250 files; logs of 7f20923e vs ef9007f7 identical after normalisation on 6 files.
- Open limits: sharing stops at quantifiers/lets; their bodies still render as trees (tests `TermToSMTLib2ConverterTests.scala:43-50` deliberately preserve exponential rendering inside quantifiers). Only 3 corpus files activate sharing.

### 2.2 Split evaluator and term-traversal dispatch (landed)
- What: splits `Evaluator.eval2` (11,356 bytecodes -> 2,251) and `State.utils.recurse` (10,936 -> 3,488/3,086/3,644) so HotSpot compiles them (default `DontCompileHugeMethods` skips methods >8000 bytes). `TermToSMTLib2Converter.render` (9,592) and `TermToZ3APIConverter.convertTerm` (8,197) remain >8000.
- Commit 7f20923e, branch `perf/split-eval2`, on main.
- Effect (all cold JVM, wall):
  - `split-eval2-20261021/report.txt`: medians poker 82.70 -> 76.83 s (+3.42%), selection_sort 57.28 -> 53.07 s (+10.66%), nested_struct 48.63 -> 58.53 s (-20.34%).
  - Nested follow-up (sil-miles-4, `split-eval2-20261021/nested-followup/report.txt`): six default pairs, 4 favour refactor, median +7.34%; regression not reproduced, cause unproven.
  - miles night run (Z3 4.16, warm): "within noise on speed".
  - 250-file JDK 21 follow-up (amadeo-1): no measurable broad gain.
- Correctness: 1527 tests pass (1530 later).
- Isolated, but sizes conflict (7% vs noise). Do not cite as a proven speedup.

### 2.3 JVM flag `-XX:-DontCompileHugeMethods` (dropped as a direction)
- Not a code change. Same goal as 2.2 via a flag.
- Retracted claim: "1.6-2.2x speedup" (miles turn 14) was measured on 1 CPU, Serial GC, with profiler; profiling A (`profiling-20261021/A/report.md`): selection_sort 175.63 -> 79.54 s, nested_struct 115.27 -> 72.78 s, "mostly collector/profiling artifact".
- Unprofiled sibling pairs, G1, 2 CPUs (sil-miles-1 step 114): median wall improvement poker 9.26%, selection_sort 10.69%, nested_struct 10.13%; 9-file set wall 435.52 -> 410.75 s (-5.69%), CPU 562.62 -> 542.33 s (-3.61%), GC pauses -33.77%; four small files regress 2-4%.
- Why stopped (miles step ~202): "makes runs faster without improving Silicon".

### 2.4 Dead `%stateless` function-proxy omission (in integrated 48a8f45b; not on main)
- What: emit stateless function proxy declarations/axioms only for live definition triggers (`FunctionData.scala:148-154`, `FunctionVerificationUnit.scala:193-198`).
- Commit 515b6801, branch `experiment/dead-stateless-proxy`, in 48a8f45b (via ebcb2e15).
- Motivation (profiling B): ico: 1,743,509 / 7,165,010 = 24.33% of instantiations belong to unused proxy axioms; 26 proxies in ico, 33 in 106-f (23,530 instances), 27 in secc (1,395). This is an instantiation count, not removable wall time.
- Effect (farida, isolated, one pair, not replicated, original ico input, 359a230e -> 515b6801): wall 140.42 -> 127.21 s (-9.4%); cgroup CPU 174.313 -> 155.524 s; peak memory 2.6G -> 2.4G. Report: `/opt/bots/log/workflow-runs/run-1792735761-5cfa2f-artifacts/.workflow-run/axioms-original-ico-followup.md`.
- Correctness: consumer audit found no supported path through auxiliary/post/body-precondition axioms; relies on a construction invariant; future cross-function consumers need phase-aware analysis.
- Not measured separately inside the integrated night. A 6-pass dead-proxy-only run was approved (amadeo-3) and replaced by the integrated arm.
- Why not landed: single pair; only measured combined at night scale.

### 2.5 Guarded equivalence-class domain encoding (opt-in `--equivalenceClasses`; in integrated; default off)
- What: replaces global equivalence-law axioms with fresh opaque classes; requires all three unconditional laws structurally; keeps every axiom; never substitutes Ref identity.
- Commits: e9521216 (branches `experiment/closure-trigger-factorization`, `review/equivalence-e9521216`), review guards ee41944a, repair 724cfc8d (`fix/equivalence-trigger-review`), integrated via 7d9d797b.
- Effect (pearl, isolated flag vs control, warm JDK 21, off-corpus mechanism targets): 106-f 273.18 -> 6.77 s (40.4x wall; step 116 table lists 6.61 s); modp: control times out at 300 s, candidate verifies in 30.56 s (censored control). Six selected night250 Py files: outcomes identical, summed warm wall -15.1%, `filter_by_substring` +34.9% (regression). Pearl: no broad 10x claim.
- Correctness: pearl's trigger audit found a completeness counterexample (diagonal `{R(x,x)}` patterns can vanish through simplification); candidate refuses that relation with a warning. gurathin's repair refuses Old/LabelledOld, nested wrappers, Let, Unfolding/Applying in affected triggers; reconstructed-counterexample modes rejected with feature on. Verdict: "accept for capped experiment", not upstream-ready. 51 CLI checks / 13 diagnostic groups identical ON vs main (amadeo-7).
- Why not landed: `filter_by_substring` regression; no completeness guarantee. Never measured in isolation on the full night corpus.

### 2.6 Sequence-axiom weight (`perf/seq-axioms-weight`, not landed, open decision)
- Update 2026-10-27: settled by seeded data (14.4). W50 is speed-neutral on 250 files, removes the permutation-file seed blow-ups and speeds testTreeWand 0.68-0.78; it loses atomic.vpr 1.88, auction_token_alloc 1.49 and suite testTranspose. The pair_insertion_sort effects below are seed noise.
- What: `@weight("50")` on `Seq_append_index3` and `Seq_drop_index2` (the reverse-direction index axioms that mint shifted index terms and drive matching loops on VerCors permutations and Gobra pair_insertion_sort).
- Commit c4196430 (also 8226d6f4 as `night/seq-axioms-weight`). Weight 10 also tried.
- Effect, conflicting:
  - Night run (miles step 340, Z3 4.16): pair_insertion_sort verifies on W while main reports one error; speed within noise; suite passes.
  - `loopfix-20261022/RESULTS.md` (sil-miles-4, 7 GB scope): timsort1 22 errors same as main, Silicon time 12.7/12.5 s -> 9.5/9.3 s (w50); pis still OOM at weight 10 (~57 s, like main) and at weight 50 after 145-172 s. Suite 1530 passed; wall 530 s vs 419 s (single run).
  - The two setups differ (memory cap, variant); unresolved which holds. Pair_insertion_sort "verifies" on integrated 48a8f45b after eight Z3 restarts per repeat is not a validated win.
- Correctness: no suite failures at weight 50.

### 2.7 One-way sequence axioms; no-reassume of proven quantifiers (rejected)
- What: (a) `perf/seq-axioms-oneway` e029989c removes append_index3 and drop_index2; (b) `perf/no-reassume` 572bef2b stops re-assuming a quantifier just proven by `Consumer.evalAndAssert` (Consumer.scala:430).
- Diagnosis (`qiprof-20261022/FINDINGS.md`): pair_insertion_sort self-matching loop from Gobra trigger `{xs[k]}` on a re-assumed invariant, then quadratic `sorted` body (1.30M of 1.78M instances; 5 GB). VerCors permutations (timsort1, quick, selection): false assertion `xs[1..] ++ ys == xs ++ ys[1..]` in `lemma_count_app` triggers a shift loop through the reverse Seq axioms (945k instantiations, 4.8 GB).
- Replay results: deleting re-assume lines -> unsat 5.9 s, 213 MB (from runaway); removing the two reverse axioms -> 5.7 s, 142 MB, all other queries identical; removing Seq_take_index's second pattern loses the proof.
- Real builds (`loopfix-20261022/RESULTS.md`): no-reassume and oneway each fix timsort1 only; pis still OOM; combined (d21d8725, unpushed) pis 1 error at 39 s on Z3 4.8.7 but OOM on 4.16.0. Test suite (Z3 4.16): no-reassume loses 4 tests (adt example_3, silicon 0282, quantifiedpredicates triggers + MaskHeapMode); oneway loses 6 (bsearch, list_insert, example-list x2, QPWands, 0896).
- Rejected: loses proofs, OOM not fixed.
- Update 2026-10-27: narrow variants of both were built behind flags (14.7). Narrow no-reassume keeps the four tests the broad form lost but needs a6fb554a gated; one-round reverse axioms keep list_insert and example-list but still lose bsearch, QPWands, 0896. Neither is landed.

### 2.8 Injectivity proof portfolio (not landed; null on corpus)
- What: when a closed unary injectivity assumption's check returns unknown (after a 10 ms probe), run fixed side-solver variants with a fresh guarded left inverse and accept only framed UNSAT. Opt-in `-Dsilicon.injectivityProofPortfolio=true`.
- Commits: native typed design cd6fb70b, 33311d98, 56048a19, b843ba96, 929938fa, 6677df37, 9677ee6e, d6e783c4, f43ef5fc, 23360623, a84dac60, final e36486a0 (`perf/injectivity-proof-portfolio`). Earlier python `--z3Exe` proxy: scratch only, rejected by review for false UNSAT (unconditional left inverse for f(0)=f(1), binder capture, symbol aliasing).
- Effect:
  - yarrow-era, in a combined alias-deferral+portfolio build, 10 cases x 3 passes: sum of medians 125.031 -> 10.847 s (11.53x); linked-list-qp-append 99.779 -> 0.417 s (239x); Knights 6.48x (this part is alias deferral); others ~1.0.
  - Native-only, full 1198-input suite (run 1792205720): sum of per-input median CPU 142.41 -> 124.17 s (1.147x), but without append 122.27 -> 123.74 s (0.988x). Append 11.9/28.4 s -> 0.43 s.
  - Corpus (seivarden/adelis paired quick, n=87): overall 0.999 [0.990, 1.008], no outcome changes, no group effect. Triggered on 1 of 1,198 inputs (append).
- Correctness: with `--proverEnableTimeBounds`, testTreeWandE2 timed out at 600 s (baseline 3.139 s; outcome flip from expected assert.failed). Full suite outcomes otherwise match.
- Why stopped: gain confined to one input; corpus flat.
- Update 2026-10-27: linked-list-qp-append is now fixed by the narrow sequence-distinctness rewrite (14.5, landed flag-off): 139 s → 1.1 s median over 10 seeds.

### 2.9 Alias deferral (`--deferBasicAliasMerging`; not landed)
- What: new BasicChunks merge only with syntactically identical args; prover-backed alias matching deferred to consolidation; guarded alias facts per new chunk (`eq && perms>0 ==> snaps equal`; field perm-sum <= write bound). v3 adds `BasicMergeIndex` (per-call hash index replacing linear `findChunksWithID` scans).
- Commits: v1 ad78565e/808c3f09 (`perf/deferred-alias-evaluation`, incomplete: loses snapshot/permission facts after partial consumption), v2 43a45a1e/af36a80d/55bdf1bf/24cfc6f9 (`perf/defer-basic-alias-merging`, `perf/guarded-alias-deferral`), v3 e49140e5/8de1ec63/659803c7 (`perf/deferred-alias-merging`), reconciled 3701dfb7 (`perf/alias-deferral-reconciled`, option default off).
- Effect (isolated, yarrow): Knights Z3 CPU 12.82 -> 2.60 s (4.94x); with index 2.587 -> 2.306 s (-10.9%); Z3 check-sat count 12,102 -> 1,253. Uniswap 4.77 -> 4.88 s (+2.24%, all 3 pairs worse); QP +14%; append 544 s ON vs 33 s OFF once, not reproduced (identical SMT; identifier-naming sensitivity: alpha-renaming alone moved that query ~99 s -> 28 s).
- Corpus (adelis, paired quick, n=85): overall 0.966 [0.906, 1.029]; Vi group 0.849 [0.746, 0.966].
- Correctness: PyP `nagini/sif_mpp/...secc-db-pred.py.vpr` verified -> failed ("insufficient permission to access ar_sec"); ListListQueue timeout -> failed. Flag-on suite: two Silver annotations for known incompleteness (0286, issue_0184) now verify (stronger facts than master).
- Why stopped: regression and ~3% corpus gain; campaign abandoned 2026-10-21. `BasicMergeIndex` cost on single-chunk produce never measured.

### 2.10 Skip disjoint full-field alias checks (not landed)
- What: in Executor `ast.Inhale` of a FullPerm field chunk, assume `x != y` against same-id full peers and skip prover alias checks (full+full is infeasible). `-Dsilicon.skipDisjointFullFieldAliases=true`.
- Commits: 0ca3d8f8 (rejected by review), 0b4bff60/92cc6bcd/8e69a3a4, f0ef3ee5, d9a91673, 4a39026f (`perf/skip-disjoint-full-field-aliases`). Diagnostic counters e6ca58e0.
- Effect: Knights one pair CPU 16.37 -> 9.56 s (1.71x), wall 1.48x; 1198 suite controlled: 133.55 s vs 148.78 s solver CPU (+11.4% slower), second run hit the 150 s cap on append. Quick corpus: 122 paired inputs, no stats produced; adelis unofficial geomean B/A 1.0018 over 32 files, no outcome flips.
- Counters: Knights 3,453 of 10,907 alias attempts (31.66%) qualify.
- Why stopped: suite slower, no corpus analysis; machine time reclaimed by operator.

### 2.11 Bounded proof cache (not landed; never run on corpus)
- What: cache successful `Decider.deciderAssert` proofs within bounded solver scopes; invalidated on pop and raw prover context weakening; `-Dsilicon.disableProofCache=true`.
- Commits d32c7f7c/33415732 (first), 17851e48 = 4f0d5f03 (fix; `perf/bounded-proof-cache`, `perf/z3-cpu-scout`).
- Effect (yarrow, isolated flag A/B): first version Knights +37%, Uniswap +8% (slower); fixed version Knights median -0.17% (neutral), Uniswap off 4.686/4.669/4.591 s vs on 5.240/5.356/5.579 s (+14.71%). Review found an invalidation blocker (raw solver weakening) before the fix. Corpus: "not run".
- Why stopped: Uniswap +14.7%, no Knights gain.

### 2.12 Hot-path allocation / lookup fixes (in integrated; not on main)
See section 5 (Store lookup, sharing precheck).

## 3. Solver interaction and queries

### 3.1 Skip futile checks: Brancher literal shortcut (D) and Decider shortcuts (rejected)
- Brancher part: skip the branch feasibility probe when the condition is literally True/False. Commits 911307b9 (`perf/skip-futile-checks`, full version, base 7f20923e), 96595263/af9a7fcf (`perf/stats-brancher`, `night/stats-brancher`).
- Decider part: remove four unknown Ref equality/disequality checks (in 911307b9 only).
- Effect: brancher-only 12 alternating pairs: secc/ico verify both; 106-f baseline timed out at 300 s twice vs candidate verified 58.31/57.28 s. Full version: secc 243.06/234.89 -> 223.65/217.31 s; ico 204.12 -> 166.25 s and 317.42 -> 169.33 s.
- Correctness: pair_insertion_sort verified -> OOM (122.4 s vs 50.5 s baseline); 118-get_closest_vowel baseline 18.49 s vs candidate timeout 306.54 s (600 s: 27.76 vs 521.24 s); Flo dijkstra-buggy failed -> timeout (not reproduced at 300/600 s); list_insert 6 s -> >16 GB OOM-killed ~214 s with the Decider shortcut. Mechanism replay: removing the four Ref checks reproduces a later unlimited-query stall even when all set-options are kept.
- D alone, night run (Z3 4.16): speed-neutral; wireguard/initiator_main goes to timeout. 5-arm cold run: D vs C geomean wall 0.991, solver CPU 0.960 (single sample).
- Why stopped: acceptance criteria (zero outcome changes) not met. Brancher part isolated (list_insert 6.48 s still passes); Decider part fails the list_insert gate.

### 3.2 Decider: skip checks contradicted by exact path assumptions (E; `perf/stats-decider`; landed 2026-10-27 with the retry as V3, see 14.3)
- What: answer "false" early when the path conditions already contain the negated goal.
- Commits 03efa778 (`perf/stats-decider`), 09af9ab7 (`night/stats-decider`, also `wip/decider-contradiction-skip` e05ee46c).
- Effect: only clear corpus speedup found. Night run (Z3 4.16, warm, vs three main replicates): wall geomean 0.92683 (bootstrap CI [0.88733, 0.96918], n=232), solver CPU 0.87739 [0.82910, 0.93013] (n=217); finished-by-all solver totals ~3,290 s -> 2,501 s (-24%). Outcomes main 123/109/14/4 (verified/failed/timeout/crash) vs E 124/110/12/4; three files improve (two timeouts answered, pair_insertion_sort verifies); none worse. 5-arm cold run (4.8.7): E vs D mean solver CPU 12.065 vs 13.733 s (derived ratio of means 0.88).
- Correctness: breaks `wands/examples_paper/list_insert.vpr` in the test suite (works on main in 6.2 s). Camille's diagnosis (`269bfd69`, `list-insert-report.md`): omitted checks return unknown, removing them changes Z3 search history, an unchanged postcondition times out; restoring any one of four probes restores fast verification; reproduced as main 0, D 0, E 1 (30 s).
- Why stopped: list_insert; history-sensitive Z3 behaviour. Not in integrated 48a8f45b. An un-fixed lead (see section 8).
- Update 2026-10-27: fixed by the fresh-prover retry and landed as V3 (14.3). Seeded: skip alone wall 0.923 [0.892, 0.955] (n=154); V3 wall 0.957 [0.923, 0.991] (n=159), solver 0.922 [0.890, 0.954] (n=175), both biased slightly in E's favour. The "no file worse" claim does not hold: failing files near the timeout get about 1.3x slower.

### 3.3 Propositional-only fixed resource budget (in integrated 48a8f45b; default off)
- What: internal Boolean feasibility/consistency checks whose goal is exactly True/False/Bool var under Not/And/Or/Implies get a positive budget `min(original, 1000)`; zero stays unlimited; any theory atom excludes the goal. `-Dsilicon.propositionalChecks=true`; StdIO/push-pop/default resource mode only.
- Commits: c50fca52 (`experiment/propositional-query-budget`), 86567a74 (`experiment/propositional-budget-fixed`, `validate/frozen-86567a74`); extracted as 48a8f45b/a6bd4fea. 86567a74 itself is not an ancestor of 48a8f45b.
- Effect (roic/amadeo-6, isolated vs fresh main 359a230e, warm, one sequential pair, then a reverse-order replication; not repeated further):
  - secc: 182.875 -> 20.356 s (8.98x); reverse arm 190.297 -> 20.362 s (9.35x, derived). Solver CPU 168.73 -> 11.95 s.
  - ico: 178.122 -> 108.259 s (1.65x); reverse 138.537 -> 111.793 s (1.24x, derived). Internal queries 20,384 -> 19,051; obligations 2,109 -> 1,999.
- Correctness: list_insert and negatives retain exact diagnostics; suite 1111 passed, same one 30 s timeout as main, 53 canceled, 33 ignored. Risk: contradictions in quantified contexts can need >1000 resources and lose pruning (3 of 7 successful false ico goals exceed 1000). modp and linked-list-qp-append still time out.
- In the integrated night this effect did not show in the totals (section 7); Flo list-buggy regressed 15.9x/12.5x there but attribution to this feature is unknown.

### 3.4 Lower `checkTimeout` 10 -> 2 (proposed; not applied)
- Existing option (Config.scala:228-237; nominal units, 0 = unbounded).
- Effect (profiling B follow-up, cold, 1 logical CPU, Serial GC, 2 reps each, inputs ico and secc only): ico 168.029 -> 127.433 s (-24.16%), secc 145.256 -> 44.572 s (-69.31%); budget 1: ico 118.864 s, secc 42.997 s (single runs). All 10 runs verified.
- Caveat: only two Nagini inputs; the report says "do not lower global default on this evidence". Inconclusive checks can explore dead paths.
- The propositional budget (3.3) is a narrower version, measured warm.

### 3.5 Scope-aware adaptive consistency budgets (rejected)
- What: repeated consistency probes in an unchanged solver context get a reduced budget after a failed check; history invalidated by push/pop/declare/option/memory-out. Commits bc571249, 4f77ba8c (`workflow-run/run-1792735761-5cfa2f-consistency-0`).
- Effect (bex, isolated): 520 secc budgets reduced; eliminated no queries; secc two pairs wall 147.991 -> 145.980 s (1.36%), CPU 158.371 -> 156.509 s. An "apparent 10.8%" ico figure in the consistency report was not analysed.
- Rejected for overnight: reuse reaches too few checks; below run variability. Earlier scope-aware consistency cache also rejected.

### 3.6 Periodic / scheduled internal-query progress (rejected)
- What: experiments with positive internal-query budgets plus periodic solver progress and purpose-separated schedules. Commits 2e87173f, bb034c16, fe97580c (`experiment/internal-query-progress` 0e6dd0ce, f90b4f44, 678a4584), attribution 0b654a06/58d6f9bd (`experiment/internal-query-attribution`), a6bd4fea.
- Effect (roic): up to 47% more internal queries, 32% more obligations, list_insert slowdown without startup warm-up, one 7 GiB OOM in the suite. The surviving design is 3.3.
- Attribution/counters (0b654a06) are in integrated 48a8f45b as instrumentation.

### 3.7 Tiered / dynamic E-matching toggles (rejected)
- Idea: cheap tier (E-matching off / qi cap) for internal checks, full fallback. Also: two-tier budget checks (miles).
- Evidence (profiling B, offline replays): original-unknown checks are 98.79% (ico) / 99.97% (secc) of bounded-check time, so tier-1 + fallback redoes the work. E-matching-off preserves 48.75% of ico unsats and 91.97% of secc. Restoring the option leaves later unlimited checks changing unsat -> unknown (probe.smt2). miles: "doesn't work in the form I proposed" (unsat checks are 1.9 s of 157 s budget-limited time in ico). roic: runtime E-matching/qi-cap toggling rejected as a parameter-only fallback.

### 3.8 Solver config diagnostics (rejected)
- `smt.arith.solver=6`: Knights 11.91 s, Uniswap 15.57 s (~3x worse Uniswap, 1 trial). `--stateConsolidationMode 3` (MinimalRetrying): Knights 0.391 s but 3 verification errors. `--proverSaturationTimeout 0`: Knights 19.83 s vs ~12.6 s baseline. Baselines Knights ~12.6 s, Uniswap ~4.7 s. Single runs. JDK 25, assertTimeout 60 s/10 s: no speed effect (assertTimeout 10 s only turns five timeouts into errors).
- Parallel verifiers (xochitl): 4 workers ~1.14x overall over 11 examples x 7 reps (exploratory, shared desktop): Uniswap 16.2 -> 10.7 s, Knights Tour 18.7 -> 22.0 s.

### 3.9 Query profiler (`-Dsilicon.queryProfile=true`; not landed, untested)
- Commit 8c3ff133 (`perf/query-profile`): attribute each prover assert/check/saturate to the nearest symbolic-execution frames. "No findings were ever recorded". Re-proposed as the data step for consistency design; never built on the new line.

### 3.10 Z3 memory-out recovery and warm runner (landed; robustness)
See section 1. pair_insertion_sort with `memory_max_size=4000` (Z3 4.16): fix restarts Z3 7 times and finishes in 6m04s with one error; base aborts at 2m34s (ProverInteractionFailed).

## 4. Path conditions, state and JVM data structures

### 4.1 Local structural membership index for ordered path conditions (rejected as submitted; repaired; not landed)
- What: private structural membership index (`PathConditionIndex`) inside ordered path-condition sets; keeps insertion order, exact equality, alias and push/pop semantics; global `Term.hashCode` unchanged. 4 files, 190 insertions.
- Commits 68136aaf (`local-pc-membership`, `review/pc-index-contract`); repair e21307b4 (`repair/pc-index-total-policy`, `review/pc-total-policy-independent`). Not in 48a8f45b; excluded from the night.
- Motivation: later-request profile `Second.equals` 18.04% JVM CPU (desdemona: 14.53% of 11,598 samples), collision paths in `HashCollisionMapNode`.
- Effect (delin, matched runs, warm, exploratory, not converged; C/B ratios):
  - Equality CPU on bam 19-20% -> <0.1%; functional 13-15% -> <=0.05%; residual index CPU 1-2%.
  - Membership probes per request 16,056,584 -> 42,259.
  - Wall bam 1.194 (trial 0), 0.980 (trial 1); functional 0.760, 0.895. Solver CPU bam 1.406/1.261; JVM CPU bam 0.996/0.874. Allocation 0.967/0.985.
  - Conclusion: equality CPU collapses but wall gains are inconsistent; do not pool into a speedup.
- Correctness: submitted version used a narrower hash callback (`from[Any]` throws on valid widened operations) and Int sets compared asymmetrically with equal Long sets (P2). e21307b4 makes policies total; rechecked and "accepted for capped experiment". Builder loses the index on filter/diff/map/empty (fallback only).
- Why stopped: delin rejected as submitted; amadeo-8 recommended independent design review, not landing.
- Isolated (flag-less: single feature vs baseline); repair measured only for correctness.
- Update 2026-10-27 (alloc stage, 14.7): no allocation or steady-state RSS change; bam JVM CPU 0.961 [0.930, 0.989], wall 0.964 [0.934, 0.991]; first-pass RSS +10%. Branch `alloc/pc` 7558acbb on 518bd9a0.

### 4.2 Global term hashing/equality collision fix (proposed only)
- desdemona: `Second.equals` is collision-driven (97% collection ancestry). Changing global term hashing risks collection/solver ordering; not attempted. Scoped alternative is 4.1.

### 4.3 Operation-local memo of function-precondition traversal (left experimental)
- What: identity-keyed cache inside one `FunctionPreconditionTransformer` invocation; discarded afterwards (no interning, no cross-call). 92 insertions incl. 65 test lines.
- Commit c01c78a3 (`workflow-run/run-1792735761-5cfa2f-compact-terms-0`), seat ista.
- Effect: synthetic DAG allocation "improved substantially" (candidate 3856 allocated; baseline figure not in the summary); representative workloads: "speedup was not established"; ista's table has qsort 10.9% slower on average wall (as quoted by starling). Tiny FPT workloads; cold-JVM variance.
- Correctness: 7 tests; 500 differential outputs match.
- Why stopped: no representative gain.

### 4.4 `PathConditions.declarations` recomputed per call (untested observation)
- `PathConditions.scala:285` and `:597`: `InsertionOrderedSet(layers.flatMap(_.declarations)) // Note: Performance?` rebuilt on every call (arada). No wisp measured it; unknown whether 4.1 touches it.

### 4.5 Projection/traversal counts (measurement only)
- Path-condition projection visits 12,512,181 (baseline) vs 8,333,661 (candidate) for 34,744 vs 26,792 unique references (visit/reference 360 vs 311); layer clones 3,805,099 vs 3,520,161 (delin). Index CPU only 1-2%. Verdict: counts alone do not justify memoization and its lifetime/config risks.

## 5. Rebuilt-structure fixes (Store, sharing analysis) and the term graph

### 5.1 Store map lookup projection (`302e29e2`; in integrated 48a8f45b; not on main)
- Update 2026-10-27: the broader control passed (14.2). Seeded, 250 files × 3 seeds: wall 0.951 [0.909, 0.993] (n=155), files ≥ 5 s 0.892 [0.822, 0.953] (n=59); no stable outcome changes; suite clean. Ready to land; `night/20261025-S` 91a4c459 applies cleanly to main 7e42b83e.
- What: `MapBackedStore.get/getExp` rebuilt the entire term/expression map (a full projection of the store) on every single-variable lookup. Now a single underlying-map lookup. Public full projections unchanged. 51 insertions, 5 deletions.
- Commit 302e29e2 (`investigate-real-workload`; integrated via e2263af3). Seats starling, desdemona.
- Effect (desdemona, isolated vs main, unprofiled warm, 3 repeats per arm, Ru2 bam_load):
  - MemorySafety wall 64.647/69.934/58.673 s -> 33.048/37.835/28.348 s (median 64.65 -> 33.05 s).
  - MemorySafetyWithFunctional 39.758/38.736/37.528 s -> 19.154/16.412/17.704 s (median 38.74 -> 17.70 s).
  - JVM CPU MemorySafety median 65.35 -> 32.09 s; total CPU 70.81 -> 39.00 s.
  - refcell (Ru3 negative case): no speedup claimed (fails identically in both arms).
- Work removed: refcell 12,928 `getExp` lookups visiting 529,197 old projected-map entries (max store size 59); bam ~3,840/3,576 entries projected per lookup, 163.5M/122.3M total (starling).
- Correctness: tests for updates, right-biased unions, absent keys, None expression values, equivalent AST keys, immutable updates.
- Why not landed: needs combined residual profile and broader controls. Both starling and desdemona state it is not a tenfold end-to-end result. Only the bam_load family (Ru2) was measured; the effect on the 250-file night is inside the integrated build (section 7), where bam_load variants improved to 0.27-0.29 of main.

### 5.2 Structural-sharing size precheck (`e544f5f9`, `a696fba7`; in integrated; not on main)
- What: before building a structural sharing analysis for SMT conversion, a bounded identity-based unfolded-size precheck (saturating at 10,000; memoized subtree sizes per incoming edge) skips analysis for provably small term trees; a696fba7 bounds precheck traversal and respects binder boundaries. Background: each eligible conversion built a fresh sharing analysis even when thresholds rejected it (amadeo-2).
- Commits e544f5f9, a696fba7 (`request-bounded-profile`, `real-jvm-validation`), integrated via e2263af3.
- Effect: only combined (Store-only -> Store+precheck): starling bam MemorySafety 23.49 -> 13.05 s, functional 16.63 -> 11.93 s, refcell 7.54 -> 8.17 s (slower); desdemona second batch ratio 1.80x / 1.39x / 0.92x. Store-only timing shifted between batches; do not multiply the two batches. No isolated number for the precheck alone.
- Correctness: 7 tests incl. a 162-identity DAG with more than Long.MaxValue unfolded nodes; a unique-identity implementation is rejected by the huge-DAG test.
- Why not landed: no combined residual profile; broader controls not run.

### 5.3 Other rebuilt-structure candidates seen in profiling (proposed only)
- Generic term transform (`state/Utils.scala:153-161, 300-309`) rebuilds every occurrence with no memo table (amadeo-2).
- Greedy consume `p - min(p,q)` (ChunkSupporter.scala:179-190), complete exhale min/subtract chains (MoreCompleteExhaleSupporter.scala:340-367), quantified consumption (QuantifiedChunkSupport.scala:1666-1679): preserve DAGs earlier rather than rely on rendering sharing.
- Consolidation fixed point (StateConsolidator.scala:70-110, 183-215) repeated merges until fixed point.
- Global memoization of `Evaluator.eval` rejected (effects: heap reads, unfolding, old states, errors, trigger recording).

## 6. Frontend (Silver) allocation

All Silver SHAs below are in the Silver submodule and were not verifiable in this checkout.

### 6.1 Macro statement list linearization (not landed; "retain for review")
- What: replaces repeated list append in Silver `MacroExpander`, which copied the prefix each time, with a List builder (~15 lines).
- Commits: palli Silver 5c2469bd, Silicon gitlink 73ef6ce3 (`frontend-allocation-prototype`); balthazar Silver 5866b21e, Silicon gitlink 2c697313 (`frontend-combined-allocation`).
- Effect (palli, isolated; bam_load Block--load MemorySafety, 174 requests): `copiedPrefixCells=27073870` per parse; 654.6 MB/request less in the macro phase; total allocation 531-701 MB/request less; wall deltas -2.09 s, -2.55 s, +2.99 s (balthazar's identical experiment same). Latency effect not established (sign flips).
- Correctness: 29 frontend tests; 72 differential controls match; all requests succeed.

### 6.2 Reserved keyword name set reuse (Silver 8d544cb5; not landed)
- What: reuse the immutable reserved-keyword set instead of rebuilding. Branch `frontend-reserved-names-investigation` 8c9423bb; combined with 6.1 in `frontend-combined-allocation`.
- Effect: combined only. Allocation -0.858-0.966 GB/request on bam, -0.308 GB on refcell; wall bam +1.518/-0.161 s; refcell +0.458 s. Frontend phase wall bam -0.086/-0.311 s; refcell +0.222 s.
- Correctness: 73 differential controls, 208 requests keep outcomes. Stopped: wall not improved; refcell regressed.

### 6.2a Update 2026-10-27: 6.1 + 6.2 together, measured for non-wall value
- jesyspa/silver `perf/frontend-allocation` bda9a9da (17 lines on b4094c37). Allocation per request 0.980 [0.977, 0.983] bam, 0.916 [0.908, 0.924] refcell, 0.966 [0.960, 0.972] Nagini; bam GC pause 0.906 [0.838, 0.989]; no latency change; suites clean (14.7). The refcell regression in 6.2 did not reproduce. Worth landing as allocation hygiene.

### 6.3 Frontend checked-input reuse / parse caching (not attempted)
- Excluded by task contract in palli/balthazar/desdemona; worth investigating only if the real API can reuse checked inputs.

### 6.4 Request-level profile of reconstruction (measurement, not an optimisation)
- amadeo-5 (`~/tmp/request-bounded-profile/report.md`, may be gone): corrected later allocation 20.367 GB; frontend roots 68.90%, path conditions 9.93%, SMT conversion 8.07%; JVM CPU later: JIT 2.06%, GC 23.04%, equality 18.04%. Ranked mechanisms: frontend reconstruction churn; ordered-membership collisions/layer reconstruction; request-wide GC; SMT reconstruction.

## 7. Integrated night, 2026-10-23 (48a8f45b)

- Build 48a8f45b = 359a230e + 515b6801 (dead proxies) + 302e29e2/e544f5f9/a696fba7 (Store, sharing precheck) + e9521216/724cfc8d (equivalence classes, opt-in) + propositional budget extract + attribution counters. Numbers below are from amadeo-11's analysis of the runbook by amadeo-3.
- Arms: O = 572cf2c8, M = 359a230e, I = 48a8f45b; 2 repeats, 1,500 requests, 250 quick files, 4 h 18 m, warm JDK 21 / Z3 4.16, 3 lanes x 2 CPUs, 3 GB Z3 cap, no cgroup OOM.
- Completed-path wall ratios: I/M 0.978 (n=233) and 0.960 (repeat 2, n=234); I/O 0.917 / 0.878 (n=223); M/O 0.927 / 0.916. All-outcome wall I/M 1.025 / 1.008 in the table but "+2.51% / +0.80%" in the summary line; not reconciled.
- Regressions: Flo list-buggy I/M 15.94x / 12.53x (5.38/6.87 s -> 85.79/86.06 s; integrated alone logs one internal Z3 memory-out restart per repeat; attribution unknown); Py 080-is_happy 8.37x / 5.80x. Improvements: PyP secc-cddc .134/.114; two Ru2 bam_load variants .27/.26 and .29/.27.
- Outcomes: no verified->failed; pair_insertion_sort failed->verified in both repeats after eight memory restarts per repeat (not a validated win); ListListQueue failed->timeout both repeats; NewMetrics verified->timeout and assignments verified->StackOverflow in repeat 1 only.
- Verdict (amadeo-11): "no measured end-to-end speedup landed"; component ablations proposed, not run.
- Night D/E/W (miles, 2026-10-22, 102 tasks, 7 h 07 m, Z3 4.16): E is the only clear win (3.2); D neutral with a timeout regression; W neutral (2.6). Three main replicates gave identical outcomes on all 250 files; noise about +-6% geomean.

## 8. Harness and infrastructure (landed or patch only)

- Corpus harness `utils/scripts/viper_corpus/` (curate/run/compare), 572cf2c8; outcome-classification fixes, GoC option `--assumeInjectivityOnInhale` (12 of 18 GoC files go failed -> verified), per-solver memory limits, 4c606e6f/ad5848df (on main).
- Warm-JVM mode bdc72b50; per-lane scopes and OOM classification a757b1a8; measured: fresh JVM per file is 3.7x longer per file but only 22% longer in total.
- `harness-peak.patch` (memory.peak accounting): not landed (`reports/stage-reports/`).
- Solver-only CPU benchmark with cgroup accounting 22770889.
- Operational lessons: 16 MiB stack gave 28 crashes of 250, 128 MiB cut to 3; Z3 baseline timings with mixed memory caps are for cost distribution only; other sessions' test load (load 10-28) distorted early runs; nested whole-command cgroups gave EOPNOTSUPP until solver-only roots were used.
- Baseline cost picture (capped full, 572cf2c8): verified 840 files, solver CPU 2,094 s of 14,585 s total CPU (14%; median file 0.09 s solver of 12.2 s CPU); timeouts (57) hold 14,235 s = 75% of all solver CPU. Profiling A (9 verified files + reborrows): GC 52.2% of weighted CPU, JIT 15.1%, term construction/simplification 10.1%, decider/prover I/O 8.2%, symbolic execution 6.3%, parse/typecheck 4.0%, consolidation 0.08%; wall waiting on Z3 29%, GC 48.8%.

## 9. Literature survey (wisp `kameliya/jorah@2026-10-23T07:34:59Z`, Opus, spawned by amadeo)

Jorah made no code changes and ran no benchmarks; it did not read the full text of Leino & Schulte or KLEE. Digest (scratchpad of that session, not durable): ranked proposals below. Only item 1 got an offline test (roic).

1. Bounded first attempt + fresh-solver retry (SHAKE/Boogie-style keep-going). Hypothesis: makes local cuts (checkTimeout 2, E) safe. Evidence: list_insert's identical 123 active assertions prove unsat in 0.08 s flattened or 0.23 s with live scopes in a fresh solver; the probe-deleted incremental prefix times out at 8 s (roic). Not implemented; roic: "plausible but unnecessary for this narrower candidate"; fresh unconstrained symbols cover ~2.5% of expensive unknown checks. Failing files would pay the retry (109 stable failures cost 2,388 s solver CPU per pass).
2. Per-member axiom pruning by trigger reachability (Boogie `/prune`, Dafny PR #1612, SHAKE FMCAD 2024). Claimed value: stability (29.7% of unstable queries mitigated), speed-neutral (solved -0.45%). Plan: offline census of reachable axioms first. No census was found; PyType closure axioms are relevant almost everywhere.
3. Deterministic per-check attribution (`get-info :rlimit`) and a Mariposa-style stability gate (shuffle/rename/reseed hot queries; Mariposa FMCAD 2023: 2.6% of queries unstable). Methodology; not built.
4. Split conjunctive goals / isolate nonlinear goals (Dafny `isolate_assertions`, Verus SOSP 2024). No measurement; narrow until 3 shows conjunctive goals dominate.
5. Matching-loop warnings on frontend domain axioms (Leino & Pit-Claudel CAV 2016). Diagnostic only; lowest rank. (Local analogue: the qiprof analysis already found two matching loops by hand.)
- Surveyed, not proposed: opacity/fuel (IronFleet), F* unsat-core hints, cross-run caching (Leino & Wüstholz CAV 2015; irrelevant to a single-pass corpus), KLEE-style model/unsat caches (rejected: local reuse 0 on ico, 7.9% on secc, KLEE assumes quantifier-free queries), dynamic E-matching toggles (rejected locally), trigger selection.
- Structural claim (jorah's reading of `DefaultMainVerifier.scala:215-274`, unverified): Boogie resets solver state before every VC; Silicon emits the preamble once per prover and runs all intermediate checks of a member in one incremental context — the likely root of list_insert's history sensitivity.
- Earlier advice (hana, 2026-10-21): prioritise compact symbolic terms end-to-end, profile permission construction and state merges, find repeated traversals, validate on synthetic and corpus benchmarks.

## 10. Do not retry

- Skipping broad classes of internal checks without a safety net (3.1 full version, Decider shortcuts as written): loses dead-path pruning; list_insert 6 s -> OOM; pair_insertion_sort OOM; 118-get_closest_vowel timeout.
- Cheap-tier-then-full-fallback checks and dynamic E-matching/qi-cap toggles (3.7): fallback repeats the expensive unknown work; toggles perturb later proofs.
- Periodic/scheduled solver progress and scope-aware adaptive consistency budgets (3.5, 3.6).
- Python `--z3Exe` injectivity proxy (2.8): unsound.
- Bounded proof cache as written (2.11): Uniswap +14.7%, no Knights gain.
- No-reassume of proven quantifiers and one-way sequence axioms (2.7): 4 and 6 suite regressions, pis still OOM.
- Lower sharing thresholds (1000 nodes / ratio 2) for SMT lets (2.1): six outcome changes.
- Global `Evaluator.eval` memoization (5.3): effects make it unsound.
- Quoting the 1.6-2.2x huge-method-flag speedup, the "5% + 7% = 12%" sum, hana's ">16x" reborrows ratio, or the "Z3 is 14% of CPU" headline without its qualifier (retracted).
- Measuring candidates unpaired or under shared-machine load, or on Z3 4.8.7 when 4.16 is pinned.
- Reading `pair_insertion_sort` failed -> verified as a win when it follows Z3 memory-out restarts.
- Spending more machine time on skip-disjoint (2.10) and alias deferral (2.9) as they stand: <=3% corpus gain, one outcome regression, suite slower.
- Changing Z3's `eager_threshold` default to 30 or 10 (14.7): neutral on the geomean, ≥4.4x losses on some files, E10 breaks uniswap_alloc.
- ParallelGC or a larger G1 young generation as the default (14.7); `MaxHeapFreeRatio=20` as a runner default (+49% JVM CPU on GC-heavy files).
- The wide sequence-distinctness rewrite (testTranspose ≥3.4x) and an unbounded retry with a new seed (suite 0175 timeout) (14.3, 14.5).
- Reading a single-seed or 2-verifier same-seed difference as an effect; reading a 3-seed outcome difference on a flip-class file as a break (initiator_main, 14.3).
- Quoting `night.py --baseline` ratios without the position bias (14.8).

## 11. Open leads

Revised 2026-10-27 from the seeded data (section 14). Ordered roughly by measured value per unit of work.

1. **Store lookup fix (302e29e2) on main.** Seeded wall 0.951 [0.909, 0.993] (n=155), 0.892 on files ≥ 5 s; suite clean (14.2). Land; then the sharing precheck (lead 4) on top.
2. **Fix the baseline-position bias in `night.py`** (14.8): interleave baseline seeds, or take caps from the profile. Until then keep an A/A arm in every `--baseline` night.
3. **Done 2026-10-27: the sequence-distinctness rewrite is on by default** (14.5): fixes the worst suite lottery (linked-list-qp-append); fires in 12 of 62 checked files; corpus-neutral. Needs a default-on suite run and a decision, not more corpus time.
4. **Sharing precheck (e544f5f9/a696fba7) isolated** on top of the Store fix. With it, bam Block--load allocation is 6.8 vs 58.8 GB per request (14.2).
5. **Silver frontend allocation fix (bda9a9da)** via a gitlink bump (14.7).
6. **E's remaining costs** (14.3): bound the first attempt by wall time as well as rlimit (list-buggy first attempts take 37-80 s), and decide whether failing files near the timeout may be 1.3x slower.
7. **QP inverse-function axiom triggers** (14.7, item7): `{down(r)}` on invOfFct/fctOfInv loops on FencesDblMsgPassAcqRewrite; dropping it in a replay finishes the stalled push in 25 s. Same matcher as the aux filter.
8. **Seq index axioms in Silicon's prelude** (14.4, 14.9): W50 (needs atomic/auction/testTranspose checks), one-round reverse axioms (needs translator-tagged index terms), narrow no-reassume (needs a6fb554a gated). The same axioms are culprits in binary_search_tree, ASSSP, ABFS, timsort1 and pair_insertion_sort.
9. **Reborrow change** (14.6): fix the flag name and drop the unmeasured Option B, then decide default-on. Corpus 0.969 wall, outcome-safe on 1,612 runs.
10. **Idle-runner heap** (14.8): tighten heap ratios only while a warm runner is idle; find why about 1.1 GB stays live between requests.
11. **Propositional budget (3.3) with ablation**, PC index (4.1, CPU-only gain 3.6-3.9% on bam), `PathConditions.declarations` (4.4): unchanged from before, not run in this window.
12. **Per-member axiom pruning census** and dead axioms beyond proxies (old lead 8); warm profile of Silicon-internal CPU (old lead 12): not run in this window.

## 13. Results of 2026-10-24 (quick corpus, warm, JDK 21, Z3 4.16 unless stated)

- **Worst integrated regressions are 2.4 (dead proxies).** Jar bisection: list-buggy 359a230e 15 s, ebcb2e15 114-130 s, 302e29e2 15 s; is_happy 7 / 74-77 / 7 s. Branch `perf/integrated-noproxy` (5855ea0d) = 48a8f45b with ebcb2e15 reverted fixes both.
- **Per-technique ablation on noproxy** (one rep; noise about +-4%; `~/data/viper-corpus-results/noproxy-flags-20261024/`): vs noproxy without flags, propositional budget wall 0.969, solver 0.916; equivalence classes 0.994 / 1.003; both 0.996 / 0.917. Noproxy without flags vs main 1.006 / 1.017. Propositional budget alone makes is_happy 6x slower (38.7 vs 6.4 s; with equivalence classes 10.4-11.7 s). Integrated vs main in the 10-23 night: wall 0.976/0.961, solver 0.923/0.922, i.e. the propositional budget; dead proxies add no broad gain.
- **SnaKt (142 tests, 5 interleaved reps):** all pass in every config; main, integrated and noproxy (with or without flags) are within 1% of each other; all 12-13% faster than original.
- **E repaired: skip + fresh-prover retry** (`perf/contradiction-skip-retry` b9209d10, not landed). Adds `--assertRetryTimeout` (default 2000 ms, converted to rlimit 20M, so deterministic): an unbounded assertion first runs bounded; on a limit hit the prover restarts with replayed state and retries unbounded. Suite equal to main; list_insert passes. One-lane corpus: wall 0.928 [0.900, 0.955], solver 0.919. Four-lane attribution (noisier): skip only 0.954, retry only 1.024, both 0.971 [0.939, 1.004]. Speed comes from the skip; the outcome wins (pair_insertion_sort, 0_append, ListListQueue) and the repair of list_insert, selection and oddEven come from the retry. Retries fire in 20-23 of 250 files (about 90-100 retries); failing heavy files pay 15-30% (dijkstra-buggy 108 -> 139 s). Cold check on the 8 files E changed: nothing worse than main.
- **Lower `checkTimeout` (3.4) on all 250 files: rejected.** ct2 wall geomean 1.068, ct5 1.159; ct2 helps files >= 10 s (about 0.8) but slows small files 10-13% and changes 5 outcomes or error lists (verified -> failed on 3 files). The 40-file sample (x0.76) was dominated by 2vyper branch-check files.
- **Huge methods (2.3): closed.** No hot Silicon method exceeds 8000 bytes; `eval2` is 2251 bytes and compiles. Only Silver `Translator.expInternal` (0.33% of samples) is skipped.
- **JDK 25 AOT cache (deployment, not code):** cache trained on 41 off-sample files; wall geomean 0.902 vs JDK 21 (noise 1.03), JVM CPU 0.797, solver 0.925; JDK 25 without cache 1.015. Gains in all size bands (0.84-0.95). Only already-unstable files changed. `~/data/viper-corpus-results/aot-quick-20261024/`.
- **Broad profile (60-file sample, `~/data/viper-corpus-results/broad-profile-20261024/REPORT.md`):** JVM 26% / Z3 74% of CPU; 10 files hold half of wall; JIT threads 33% of JVM CPU, Silver frontend 17%, no Silicon method above 3.3% self time. Identical repeated queries 1.5-3.7% of Z3 time; empty push/pop pairs 14% of pops. `smt.arith.solver=6` and saturation off regress; pipelined prover I/O neutral.

## 14. Seeded run, 2026-10-25 to 2026-10-27 (workflow run `kameliya/run-1792927685-e7215b`)

Stage reports: `/opt/bots/log/workflow-runs/run-1792927685-e7215b-artifacts/.workflow-run/<stage>.md` (night1, mloop, night2-e, method, cfg, item6-item10, night3, pass4, alloc). Data under `~/data/viper-corpus-results/`: `seed-night-20261025`, `e-ablation-20261026`, `noise`, `tiers`, `fixes-20261026`, `seqDistinctInverse-20261026`, `night3-20261026`, `pass4-20261027`, `alloc-20261027`, `impact-20261027`.

Method for every number below: Z3 4.16.0, warm JDK 21.0.10, `-Xmx4g`, 3 GB Z3 cap, 7G lane scopes, 300 s timeout, Z3 seeds through `smt.random_seed`/`sat.random_seed`, the same seeds per file in every arm. Per file the median over seeds; a file is unstable if max/min over seeds > 2 in an arm; ratio = geomean over stable files with a 95% bootstrap CI over files. Nights 1-2 ran Silicon's default of 2 parallel verifiers; night 3, pass4 and impact ran 1 verifier (`--numberOfParallelVerifiers 1`, `-Xss512m`), so do not compare absolute times across that boundary. Load from other accounts was 8-17 (up to 34 on non-lane CPUs) throughout.

### 14.1 Landed on origin/main in this run

| change | commit(s) | default | evidence |
|---|---|---|---|
| E as V3: skip contradicted checks + fresh-prover retry, retry seed = user seed + n, first attempt 1500 ms (rlimit 15M), retry bounded at 4x | 9a959ab9, dd279def, 58fe594b, 6ad9ba82 (= measured `perf/both-v3-bounded-retry` e2e3ee35) | on | 14.3 |
| Sequence distinctness in inverse-function form, whole-sequence guards only | 8bc5fd04..544c0c51 (= measured bf862649) | on since 2026-10-27 (default-on suite 1560/0); off with `-Dsilicon.exp.seqDistinctInverse=false` | 14.5 |
| Reborrow: take exactly `perms` when provably available | 7e42b83e (= measured f4589728, byte-identical) | off; reads `-Dsilicon.proto.minSimp=true` (see 14.10) | 14.6 |
| Harness: noise profile, adaptive seeds, baseline caps, tiers, GC-CPU logs, per-request JVM peak RSS | a68059cb..e96fa01e | tooling | 14.8 |
| Matching-loop analysis (`utils/scripts/matching_loops/`), per-command SMT timing | 64cc1808..b8869ecc, d47170a1 | tooling | 14.9 |
| Suite Z3 cap (`memory_max_size=3000` in SiliconTests), `utils/scripts/suite_times.py` | aa7c2fde | tests | 14.8 |
| Warm runners return idle heap (`-XX:G1PeriodicGCInterval=10000`), runner scopes in `silicon.slice` | 518bd9a0 | tooling | 14.8 |

Main with all of the above vs the original 572cf2c8 (`impact-20261027`, T2, ≥3 seeds, 1 verifier, both experimental flags passed but the reborrow flag name was wrong, so that change was off): wall 0.850 [0.819, 0.883] (n=169), solver CPU 0.912 [0.880, 0.945] (n=172), inverted from O/N 1.176 [1.133, 1.221] and 1.096 [1.058, 1.136]. Main was the baseline, so the position bias (14.8) favours the original; the gain is if anything understated. The original also crashed on 8-14 files per seed (Z3 memory-outs before d5926d69). Suite at 7e42b83e: 1558 passed, 0 failed.

### 14.2 Store lookup fix (5.1), seeded

- Night 1, S = main + 302e29e2 vs M, 250 files × seeds 0-2: wall 0.951 [0.909, 0.993] (n=155); files with M ≥ 5 s 0.892 [0.822, 0.953] (n=59); stricter 3-arm stability 0.969 [0.928, 1.008] (n=141); total CPU 0.933 [0.888, 0.978] (n=148); solver CPU 0.992 [0.976, 1.008] (n=181). The gain is JVM time: Ru1 0.71, Ru2 0.73, bam_load 0.30-0.32 on every seed.
- Outcomes: differs from M only on 4 files that already flip with the seed on M. Suite 1534 passed; the only failure is the linked-list-qp-append timeout M also shows.
- GC pause 115.7 s vs 136.9 s per 750 runs. alloc stage: with the sharing precheck it cuts bam Block--load allocation 58.8 → 6.8 GB per request and wall 4.4x (single JVM, context only).
- Status: verdict "ready to land"; still off main. 91a4c459 applies cleanly to 7e42b83e.

### 14.3 E: ablation, V2, V3

- **Ablation (night 2, 250 files × seeds 0-2, 4 arms, 2 verifiers).** Skip/M wall 0.923 [0.892, 0.955] (n=154), solver 0.913 [0.888, 0.937] (n=186). Retry/M wall 1.003 [0.972, 1.035] (n=154). Both/M wall 0.917 [0.880, 0.953] (n=156), solver 0.895 [0.860, 0.928] (n=187). The skip's gain excludes 1 on each seed separately.
- The retry carries the outcome wins, each on 6 of 6 seeds: 0_append timeout → verified, pair_insertion_sort 4/6 → 6/6 verified, ListListQueue fails in time. Error counts are identical on all 113 failing files in every arm and seed.
- Skip alone fails suite test list_insert (deterministic without a Z3 cap; 2 of 6 seeds hit the 3 GB cap with one). Mechanism: four `unknown` consistency probes change Z3's search state; the retry proves the goal in 0.2 s.
- `--assertRetryTimeout` is an rlimit, not wall time: first attempts that end in a retry take 2-18 s (median 8.1 s, max 120 s). Spent first attempts cost 12-13% of the Retry arm's wall, mostly on files that time out anyway.
- **V2** (item6, retry with a new seed, budget 1500 ms = rlimit 15M chosen from 88,654 passing first attempts: 15M cuts 7, 8M cuts 47): T0, 40 files × 10 seeds, V2/M wall 1.011 [0.987, 1.036] (n=20). list_insert passes 20/20 seeds. Lost suite test `all/issues/silver/0175.vpr` (600 s timeout; the new-seed retry was unbounded).
- Night 3 (T2, 1 verifier, adaptive 1-8 seeds): V2/M wall 0.945 [0.903, 0.989] (n=125), solver 0.924 [0.892, 0.955] (n=171), biased (14.8). Answers 16 of M's 35 timed-out runs.
- **V3** = V2 + retry bounded at 4x the first attempt: 0175 in 12.9 s, suite 1554/0. Pass4 C = V3 + seqDistinctInverse flag on (fires on one sub-second T2 file), T2, 876 runs per arm: wall 0.957 [0.923, 0.991] (n=159), solver 0.922 [0.890, 0.954] (n=175); all comparable files solver 0.897 [0.861, 0.933] (n=194), JVM CPU 1.019 [0.965, 1.078] (n=227). Corrected roughly for the position bias: wall about 0.98, solver about 0.95. Timeouts 35 → 19 runs (22 of M's 35 answered on 12 files), no verified run lost.
- Costs that hold across seeds: failing files near the 300 s limit get slower. dijkstra-buggy 1.3x and times out on 2/8 seeds (M 0/8); Flo/acyclic/dll times out on 5/8 (M 1/8). Flo list-buggy: rlimit-bounded first attempts take 37-80 s wall there.
- initiator_main: night 3 called V2's s0/s2 timeouts a break; pass4's 8 seeds give 6 verified + 2 timeouts in both C and M, on different seeds. Not a regression.

### 14.4 Sequence-axiom weight (2.6), seeded

- Night 1, W50 vs M, 250 files × seeds 0-2: wall 0.986 [0.954, 1.019] (n=155); M ≥ 5 s 0.959 [0.921, 0.997] (n=57). Outcome changes only on files that flip with the seed on M.
- Permutation files: M has a 3-7x slow seed on each of the 5; W50 keeps all at 3-10 s on every seed in two independent runs. testTreeWand ×3 0.68-0.78 on every seed. W10 stabilises the permutations but does not speed up testTreeWand.
- Losses that hold across seeds: auction_token_alloc 1.49, Ru3 atomic.vpr 1.88, suite testTranspose 1 s → 12-46 s.
- The earlier "weight fixes pair_insertion_sort" claim was seed noise (1/3 seeds verify on W50, 1-2/3 on M).
- Suite time: identical outside the 10 slowest tests (178-182 s, two runs per arm); the old 530 vs 419 s came from linked-list-qp-append plus load.
- Status: recommended W50, pending atomic/auction/testTranspose checks; not re-measured since (item10's W50 run is unpaired). Not landed.

### 14.5 Linked-list-qp-append and sequence distinctness

- Wide rewrite (item9): linked-list-qp-append ≥35x faster (n=10 seeds, 6 off-seeds capped); T0 wall 1.014 [0.939, 1.097] (n=32); testTranspose ≥3.4x slower (2/10 seeds capped).
- Narrow rewrite (bf862649, landed flag-off): linked-list-qp-append median 139 s → 1.1 s (10/10 seeds verify on, 9/10 off); testTranspose not rewritten, 6.1 vs 5.7 s, solver CPU equal; suite 0 outcome changes in 1672 cases. Fires in 12 of 62 checked files, none a corpus T0 performance file.
- This is what 2.8's injectivity portfolio targeted, with a 100-line term rewrite (src/main +99/-1) instead of a side solver.

### 14.6 Reborrows permission terms (lead 7, 5.3)

- Night 3, RB = f4589728 with `-Dsilicon.proto.minSimp=true` vs M, 536 files (499 tagged prusti_lifetime), 2-8 seeds: tagged wall 0.969 [0.953, 0.985] (n=470), solver 0.939 [0.922, 0.956] (n=491), JVM CPU 1.006 [0.990, 1.021] (n=449). Biased by about 3 points (14.8). Outcomes identical on all 1,612 paired runs; on 17 baseline-timeout files RB answers 1 of 6.
- On the 24-file reborrows family: 6.6x less SMT, 49% less Z3 CPU. The family gain does not carry to the corpus because most tagged files are small.

### 14.7 Negative and partial results

- **eager_threshold (Z3 `smt.qi.eager_threshold`, default 100 via z3config.smt2; set through `--proverConfigArgs`).** Night 3 T2: E30 wall 1.008 [0.957, 1.061] (n=126), E10 1.031 [0.990, 1.077] (n=122). T0 vs the A/A arm: E30 1.060 [1.016, 1.117] (n=41), E10 0.862 [0.701, 1.002] (n=41). Both answer timeouts (E30 13 runs on 6 files) and break others: ring-insert-sorted capped ≥4.4x (both), E10 breaks uniswap_alloc 2/2 and RelAcqRustARCStronger 1 seed, 043-pairs-sum-to-zero 4.5x. Pass4 CE/C (both non-baseline, unbiased): wall 1.011 [0.982, 1.042] (n=162). Keep 100.
- **GC.** Night 1: pauses 0.5% of request wall, concurrent cycles 1.5%, est. GC CPU 2% of process CPU; only Ru3 mendel and 2vyper alloc files exceed 5% pause share. Night 3 + post-night check (2 files, 3-10 seeds): ParallelGC cuts JVM CPU to 0.6-0.7x on uniswap_alloc and arc_rwlock but not wall (uniswap 0.977 [0.860, 1.109]); G1NewSizePercent=40 changes nothing. `MaxHeapFreeRatio=20` costs 49% JVM CPU [44%, 55%] on uniswap_alloc. Keep default G1.
- **Aux self-matching trigger filter** (item7, `exp/aux-self-matching-triggers` 58150119, flag-off): removes the aux loop on FencesDblMsgPassAcqRewrite (line 429) but the file still times out on 5/5 seeds both arms; suite identical. The next loop is in the QP inverse-function axioms (`{down(r)}` on invOfFct/fctOfInv); removing it in a replay finishes the stalled push in 25 s vs > 60 s. Closed without landing.
- **No-reassume, narrow form** (item8, `exp/no-reassume-self-matching`, Consumer +38/-1, flag-off): skip re-assuming only self-matching proven foralls. pair_insertion_sort, 10 seeds: 1 verifier 1.054 [0.711, 1.621], 2 verifiers 0.683 [0.513, 0.912]; memory-outs remain. T0 (42 stable files, 3 seeds) 1.043 [1.004, 1.085] with identical SMT on 41 files: either check overhead or drift. Flag-on suite lost graph-copy ×4 to an ill-sorted substitution; fix a6fb554a is not built or suite-gated.
- **One-round reverse Seq axioms** (item10, `exp/seq-one-round-reverse` b29eda68, flag-off): permutation file medians 2-10x lower (6 seeds, all unstable on M, so no CI); T0 wall 0.982 [0.924, 1.041] (n=32); is_happy ≥2.1x (capped seed). Loses suite bsearch, QPWands, 0896 (missing real `Seq_index` terms). FencesDblMsgPassAcqRewrite changed timeout → failed in 144-261 s although it has no Seq: unexplained, unchecked.
- **PC membership index (4.1, e21307b4) and Silver frontend fix (6.1+6.2, jesyspa/silver bda9a9da)**, alloc stage, stream runs (bam 96 requests/arm, refcell 88, Nagini 112; hierarchical bootstrap): PC index allocation 0.999-1.005 (CIs include 1), bam JVM CPU 0.961 [0.930, 0.989], wall 0.964 [0.934, 0.991], first-pass RSS +10% [2%, 18%]. Frontend fix allocation 0.980 [0.977, 0.983] bam, 0.916 [0.908, 0.924] refcell, 0.966 [0.960, 0.972] Nagini; bam GC pause 0.906 [0.838, 0.989]; no latency change; SnaKt unaffected (it skips the macro expander). Suites clean for all arms.

### 14.8 Benchmark method and harness findings

- **Seeds sample fragility; with 1 verifier they also pin it.** With 2 verifiers, the same seed gave oddEven 23 vs 57 s and flipped pair_insertion_sort between runs (night 1). With 1 verifier, an A/A arm differed from M on 1 of 587 paired runs (night 3 T0), and pass4's M reproduced night 3's M outcomes exactly (415/426/35). Cross-arm outcome changes at the same seed are arm effects under 1 verifier.
- **Noise.** Night 1: 89 of 250 files unstable on wall; median cross-seed |log ratio| on files ≥ 1 s is 0.17 wall, 0.08 solver CPU, 0.32 JVM CPU. 14 files that are seed-unstable on solver CPU carry 45-78% of the old same-seed repeat variance. Night 3: 101 of 250 files unstable in some arm; the profile under-predicts noise out of sample (quiet SD 0.08 in sample, 0.20 out).
- **Baseline-position bias.** `night.py --baseline` runs the baseline's seeds of a file before the other arms (needed for the cap). An A/A arm (same jar) measured MM/M JVM CPU 0.855 [0.771, 0.945], wall 0.974 [0.921, 1.026], solver 0.969 [0.931, 1.006] (n=42, T0, 10-16 seeds). Every `--baseline` ratio since e96fa01e favours the non-baseline arm by roughly that. Cause unknown; contention, runner age and the heap-ratio change were ruled out. Not fixed.
- **Caps.** A cap of 4x the slowest baseline seed cut 0 of 2,531 finished runs when taken from 3 seeds and 8 of 8,684 (0.09%, all noisy-class files) from 2 seeds. Capped runs that did occur were real regressions: ring-insert-sorted ≥4.4x, testTranspose ≥3.4x, is_happy ≥2.1x.
- **Cost per arm.** Old method (T2, 3 seeds, 300 s everywhere): 7.0 lane-h per arm in night 1, 6.7 in night 2. New (T2, adaptive 1-8 seeds, caps): 5.35 lane-h in night 3 pass 1 (4 arms, 3,504 runs, 4.28 h on 5 lanes), 4.67 in pass4 (3 arms, 2,628 runs, 2.8 h), against 4.7 projected. T0 (44 files, ≥10 seeds): 2.65 lane-h per arm. T1 as defined saves 0.1 lane-h against T2 and was not used.
- **Idle-runner heap.** Twenty warm runners kept about 4 GB RSS each at 518bd9a0 (about 1.1 GB live, G1 keeps about 3.6 GB committed at MaxHeapFreeRatio 70); night 3 hit 38.7 of 40 GiB. The runtime fix used that night (MaxHeapFreeRatio=20) costs 49% JVM CPU on GC-heavy files and must not become a default. Proposed: tighten the ratios only while idle via `HotSpotDiagnosticMXBean`. Not done.
- Harness records have `errors=[]`, so error sets cannot be compared from night records; night 2 compared error counts from `stdout.log`.
- The host's kill-orphan-builds timer kills user-systemd processes whose argv contains `/bin/z3 `; nights pass a hard link of the binary.

### 14.9 Matching-loop diagnoses (mloop, item7, cfg)

- `utils/scripts/matching_loops/mloop.py` reproduced every known culprit: is_happy (issubtype axioms, history-only, seed-sensitive), list-buggy (QP inverse `intf-invOfFct`), timsort1 (Seq drop/append index), pair_insertion_sort (`sorted` body fed by QP inverses and Seq take/drop).
- 10 slowest stable-timeout files (one run each, diagnoses only): loops in 4. In binary_search_tree, ASSSP and ABFS the Seq index axioms (append_index2/3, drop_index2) are among the culprits; mergeDAG is Set_subset_def plus reachability-preamble quantifiers. dijkstra, FollyRWSpinlockStronger and the ring-insert clients are shallow mass instantiation (generation 9-34), not loops.
- Correction: mloop called FencesDblMsgPassAcqRewrite "not a Z3 problem" because check-sats total 0.81 s. item7 found the time in Z3 processing a `push` after QP inverse axioms (a loop between `{down(r)}` triggers). cfg's per-command timing (d47170a1) now reports non-check-sat time; use it before calling a stall non-Z3.

### 14.11 Retry policy ablation, 2026-10-28
- Setup: one jar (f929b360), 40 files (34 where retries fired in the impact run, plus 6 failing files over 100 s), 4 seeds, 4 lanes, caps as in the impact runs. Arms: A0 skip without retry (`--assertRetryTimeout 0`); SU same seed, unbounded retry; NU new seed, unbounded retry; D new seed, retry bounded at 4x (the default before this entry). Data: `~/data/viper-corpus-results/ablation-retry-20261028/run`.
- Outcomes (160 runs each): A0 34 verified / 70 failed / 50 timeout / 6 capped; SU 38/73/49/0; NU 37/72/51/0; D 37/89/34/0.
- Wall on files with identical complete outcomes, vs D: A0 1.34 [1.11, 1.65] (n=15); SU 1.01 [0.97, 1.06] (n=24); NU 1.05 [1.02, 1.10] (n=23). SU/NU 0.96 [0.91, 1.01]. Restart and replay cost 41-54 s per arm in total.
- D's extra failures are 4 Rea files on every seed where the other arms time out (upstream also times out). Three report errors with no ExpectedOutput (`0_harris-list.vpr@84`, `1_reverse.vpr@47`, `0_ring-insert-client-closed-u.vpr@53`); only `3_ring-insert-client-failing-alpha-gamma.vpr@63` is the expected error. The bounded retry turned search into unexpected errors.
- The "1500 ms" first attempt is an rlimit (15M) and takes 4-7 s wall on these files; bounded retries ran 10-43 s wall.
- Decision: default to same seed and an unbounded retry (SU). The retry stays (A0 loses 0_append on 2 seeds and is 1.34x slower); the new seed gains nothing; the bound only produces unexpected errors. `-Dsilicon.exp.retrySeed=new` and `-Dsilicon.exp.retryFinal=bounded` keep the old behaviour.

### 14.10 Open issues found in this run

- 7e42b83e's message says `-Dsilicon.exp.exactReborrow=true`, but the code reads `silicon.proto.minSimp` and also contains an unmeasured Option B (`silicon.proto.permMacro`) and a "PROTOTYPE" comment. The impact run passed the documented name, so its N arm ran without the reborrow change.
- Intermittent `StackOverflowError` in the evaluator on Ru3/mendel/assignments.vpr (night 1 W50, night 2 Skip and Retry, 10-23 night): unexplained, not arm-specific.
- linked-list-qp-append makes suite wall time unusable (13 s to a 600 s timeout or 8 GB Z3 on main); use `suite_times.py`, or turn on the distinctness rewrite.

## 15. Per-verification startup cost, 2026-10-28

Warm JVM (JDK 21, Z3 4.16.0, 16 cores, desktop host with background load; round-to-round noise about 20%). Phase timers were temporary and are not in the tree.

- Trivial one-method file, default 16 workers, warm: 120-190 ms, against 24 ms with `--numberOfParallelVerifiers 1`. Frontend phases (parse, typecheck, translation, consistency) take 2 ms. The rest scales with the worker count, 17 provers including the main one:
  - static preamble (`z3config.smt2`, `preamble.smt2`, about 100 commands): 55-108 ms. Each command is a blocking round trip, sent to each prover in turn.
  - pool start, 24-28 ms: per prover, spawn about 1 ms plus about 1 ms until `(get-info :version)` answers, run serially.
  - teardown, 12-27 ms: `destroyForcibly` and `waitFor` per prover, serially.
  - saturation `check-sat` 13 ms, program preamble 6 ms; the method itself takes 2 ms.
- The pool is started in `configureVerifier`, before parsing, so the worker count cannot depend on the program.
- Prototypes, all off by default (`VerificationPoolManager`):
  - `-Dsilicon.exp.capWorkers=true`: start the pool in `verify` with min(workers, methods + CFGs) workers; all workers when `--parallelizeBranches` is set. Trivial file: 23 ms.
  - `-Dsilicon.exp.asyncBroadcast=true`: queue each pooled-prover command on a per-worker thread; the queue is drained before the first method task. Static preamble 55 → 4 ms and saturation 13 → 2 ms, but the program preamble went 6 → 12 ms from executor overhead on many small commands. Trivial file: 67 ms.
  - `-Dsilicon.exp.parallelLifecycle=true`: start and stop worker provers concurrently. Start 26 → 4.5 ms, stop 12 → 3 ms. Trivial file: 92 ms.
  - All three: 18 ms.
- 40 random suite files, warm, 5 rounds, last-round wall: base 15.9 s; cap 9.2; async 11.2; parallel 16.1; all 8.3. Verification results were identical in every arm.
- Silicon suite (`sbt test`, 1567 tests pass in every run): on 2 cores (2 workers, as in the suite gates) base 228 s, all three 207 s. Unpinned on 16 cores, base takes 520 s and all three 233 s (252 s when rerun on the landed tree). Per-test median: 0.217 → 0.036 s at 16 cores and 0.058 → 0.048 s at 2 cores. Tests over 1 s barely change (139.8 → 133.7 s summed), and they are slower at 16 workers than at 2 (80 s).
- Cold CLI, trivial file: 1.3 s base and 1.17 s with all three switches. An AppCDS archive (`-XX:ArchiveClassesAtExit`, then `-XX:SharedArchiveFile`, 56 MB) gives 0.61 s. The archive is launcher-only, with no code change; ViperServer does not benefit.
- Ranked remaining ideas:
  1. capWorkers by default. It is the largest win for ViperServer clients, whose files mostly have fewer methods than cores. Risks: Brancher's `parallelizeBranches` is already excluded. On reuse, `reset` tears the pool down and rebuilds it, so a reused Silicon instance no longer keeps its warm provers.
  2. parallelLifecycle by default. Low risk; it also helps programs with many methods.
  3. asyncBroadcast, after batching small commands per worker (one task per `emit(Iterable)` or per preamble section) to remove the program-preamble regression. Risk: a deferred prover failure surfaces at the first method task, not at the emitting command.
  4. Pipelined emission inside one prover: write a chunk of commands, then read the `success` replies. Bound the chunk to stay below the pipe buffers, or both processes block. This would make the memory-out replay of `lastCommand` in `readSuccess` wrong. Worth about 3 ms per prover.
  5. AppCDS for the CLI and test JVMs: about 0.55 s per cold start.
  6. Reusing provers across verifications (`(reset)` instead of respawning): after 1-3 at most 2-5 ms per verification remain, so not worth the risk.

## 12. Source index

- Section 14: stage reports in `/opt/bots/log/workflow-runs/run-1792927685-e7215b-artifacts/.workflow-run/`; data directories listed at the top of section 14.
- Reports: `~/data/viper-corpus-results/reports/SUMMARY.md`, `reports/stage-reports/{baseline,harness,alias_reconcile,measure}.md`, `profiling-20261021/{plan.md,A/report.md,B/report.md}`, `perf-skip-futile-checks-20261021/`, `split-eval2-20261021/`, `corpus-stats-20261022/report-v2.md`, `qiprof-20261022/FINDINGS.md`, `loopfix-20261022/RESULTS.md`, (`warm-night-20261023-v2-integrated/` exists but was not read).
- Workflow artifacts: `/opt/bots/log/workflow-runs/run-1792164539-606672 ... run-1792215341-0394f3` (yarrow, 2026-10-16/17), `run-1792519284-4f684d` (seivarden), `run-1792735761-5cfa2f` (consistency/axioms/compact-terms/list-insert seats).
- Wisps (`kameliya/`): yarrow@2026-10-16T14:55:42Z, xochitl, seivarden, moon, rolan, matthew, adelis, miles@2026-10-21T07:38:47Z, sil-miles-1..5, sylve, hana, sil-amadeo-1..5, jorah, roic, pearl, starling, farida, saskia, desdemona, gurathin, palli, arada, delin, balthazar, camille, ista, minthara, bex. Several have no stored summary; use `instance-info show <id> --grep`.
- Branches: `git branch -a` in the silicon repo; main-direct landing means everything unmarked "main" above lives only on branches.
- Gaps: the yarrow-era runs were read via run artifacts and a digest; miles' main transcript was grepped not read in full; Silver SHAs unverified; `/tmp/*scratch*` evidence paths may be gone.
