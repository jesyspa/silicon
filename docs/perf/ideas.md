# Silicon performance ideas ledger (2026-10-14 to 2026-10-24)

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
- Short ids: `miles`, `amadeo`, `yarrow`, `seivarden`, ... are wisp personas (`kameliya/<name>@<created>`); `sil-<name>-N` are their workers.

## 1. Landed on origin/main, with measured effect

Only the first three are Silicon source changes that affect speed. None has a clean, replicated end-to-end win on Z3 4.16.

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

### 2.8 Injectivity proof portfolio (not landed; null on corpus)
- What: when a closed unary injectivity assumption's check returns unknown (after a 10 ms probe), run fixed side-solver variants with a fresh guarded left inverse and accept only framed UNSAT. Opt-in `-Dsilicon.injectivityProofPortfolio=true`.
- Commits: native typed design cd6fb70b, 33311d98, 56048a19, b843ba96, 929938fa, 6677df37, 9677ee6e, d6e783c4, f43ef5fc, 23360623, a84dac60, final e36486a0 (`perf/injectivity-proof-portfolio`). Earlier python `--z3Exe` proxy: scratch only, rejected by review for false UNSAT (unconditional left inverse for f(0)=f(1), binder capture, symbol aliasing).
- Effect:
  - yarrow-era, in a combined alias-deferral+portfolio build, 10 cases x 3 passes: sum of medians 125.031 -> 10.847 s (11.53x); linked-list-qp-append 99.779 -> 0.417 s (239x); Knights 6.48x (this part is alias deferral); others ~1.0.
  - Native-only, full 1198-input suite (run 1792205720): sum of per-input median CPU 142.41 -> 124.17 s (1.147x), but without append 122.27 -> 123.74 s (0.988x). Append 11.9/28.4 s -> 0.43 s.
  - Corpus (seivarden/adelis paired quick, n=87): overall 0.999 [0.990, 1.008], no outcome changes, no group effect. Triggered on 1 of 1,198 inputs (append).
- Correctness: with `--proverEnableTimeBounds`, testTreeWandE2 timed out at 600 s (baseline 3.139 s; outcome flip from expected assert.failed). Full suite outcomes otherwise match.
- Why stopped: gain confined to one input; corpus flat.

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

### 3.2 Decider: skip checks contradicted by exact path assumptions (E; `perf/stats-decider`; not landed, blocked)
- What: answer "false" early when the path conditions already contain the negated goal.
- Commits 03efa778 (`perf/stats-decider`), 09af9ab7 (`night/stats-decider`, also `wip/decider-contradiction-skip` e05ee46c).
- Effect: only clear corpus speedup found. Night run (Z3 4.16, warm, vs three main replicates): wall geomean 0.92683 (bootstrap CI [0.88733, 0.96918], n=232), solver CPU 0.87739 [0.82910, 0.93013] (n=217); finished-by-all solver totals ~3,290 s -> 2,501 s (-24%). Outcomes main 123/109/14/4 (verified/failed/timeout/crash) vs E 124/110/12/4; three files improve (two timeouts answered, pair_insertion_sort verifies); none worse. 5-arm cold run (4.8.7): E vs D mean solver CPU 12.065 vs 13.733 s (derived ratio of means 0.88).
- Correctness: breaks `wands/examples_paper/list_insert.vpr` in the test suite (works on main in 6.2 s). Camille's diagnosis (`269bfd69`, `list-insert-report.md`): omitted checks return unknown, removing them changes Z3 search history, an unchanged postcondition times out; restoring any one of four probes restores fast verification; reproduced as main 0, D 0, E 1 (30 s).
- Why stopped: list_insert; history-sensitive Z3 behaviour. Not in integrated 48a8f45b. An un-fixed lead (see section 8).

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

## 11. Open leads

Ordered roughly by measured upside and available evidence.

1. **E (Decider contradiction skip) with the list_insert regression fixed.** Only clear corpus win: solver CPU geomean 0.877, wall 0.927 (n=232, Z3 4.16), no file worse. Blocker: list_insert history sensitivity (3.2). Fix options: keep the four probes' Z3 state effect (e.g. one retained check), or the fresh-solver retry (lead 5).
2. **Store lookup fix (302e29e2) on its own, on the full quick set.** 2x on bam_load (64.7 -> 33.0 s; 38.7 -> 17.7 s), tests cover semantics, never run alone against the 250 files. Land candidate once a broader control passes.
3. **Propositional budget with ablation.** secc 9x, ico 1.24-1.65x (single pairs) but not visible in the integrated night; run the ablations amadeo-11 listed (each component vs 359a230e) and find what causes the Flo list-buggy 12-16x regression in the integrated build.
4. **Sharing precheck (e544f5f9/a696fba7) isolated.** Only measured stacked on Store (bam 23.5 -> 13.1 s, refcell 7.5 -> 8.2 s slower).
5. **Fresh-solver retry / bounded first attempt (literature idea 1).** Offline evidence only (list_insert 0.08 s fresh vs 8 s timeout). Would make leads 1 and 3.4 (checkTimeout 2, -24% ico, -69% secc, 2 inputs) safe to try broadly.
6. **Path-condition membership index (e21307b4)** with a converged warm measurement: equality CPU 18% -> <0.1% but wall gains inconsistent.
7. **Permission-term DAG preservation before rendering** (5.3): rendering sharing does not help inside quantifiers; reborrows-style blowups originate in ChunkSupporter/MoreCompleteExhale.
8. **Per-member axiom pruning census** (literature idea 2) and **reachability of dead axioms beyond proxies**; 24-37% of ico instantiations were dead-proxy; PyType exclusion axiom is 99.67% of secc instantiations (7,262,524) — inspect whether it can be guarded.
9. **Frontend (Silver) work**: allocation proven lower (0.5-0.9 GB/request) but wall unchanged; frontend roots are 68.9% of allocation. Needs a latency-focused redesign, or checked-input reuse.
10. **Matching-loop defence in Silicon's own axioms**: weight (2.6) needs a settled decision (night run vs loopfix disagree); a default assertTimeout or Z3 `memory_max_size` as containment (turns OOM into bounded failure; not a speedup).
11. **Per-call cost of `BasicMergeIndex` and `PathConditions.declarations`** (4.4): never measured.
12. **Warm-JVM profile of Silicon-internal CPU** (seivarden's suggestion; GC is 23-52% of CPU): not done; SnaKt test-suite profile also not done.

## 12. Source index

- Reports: `~/data/viper-corpus-results/reports/SUMMARY.md`, `reports/stage-reports/{baseline,harness,alias_reconcile,measure}.md`, `profiling-20261021/{plan.md,A/report.md,B/report.md}`, `perf-skip-futile-checks-20261021/`, `split-eval2-20261021/`, `corpus-stats-20261022/report-v2.md`, `qiprof-20261022/FINDINGS.md`, `loopfix-20261022/RESULTS.md`, (`warm-night-20261023-v2-integrated/` exists but was not read).
- Workflow artifacts: `/opt/bots/log/workflow-runs/run-1792164539-606672 ... run-1792215341-0394f3` (yarrow, 2026-10-16/17), `run-1792519284-4f684d` (seivarden), `run-1792735761-5cfa2f` (consistency/axioms/compact-terms/list-insert seats).
- Wisps (`kameliya/`): yarrow@2026-10-16T14:55:42Z, xochitl, seivarden, moon, rolan, matthew, adelis, miles@2026-10-21T07:38:47Z, sil-miles-1..5, sylve, hana, sil-amadeo-1..5, jorah, roic, pearl, starling, farida, saskia, desdemona, gurathin, palli, arada, delin, balthazar, camille, ista, minthara, bex. Several have no stored summary; use `instance-info show <id> --grep`.
- Branches: `git branch -a` in the silicon repo; main-direct landing means everything unmarked "main" above lives only on branches.
- Gaps: the yarrow-era runs were read via run artifacts and a digest; miles' main transcript was grepped not read in full; Silver SHAs unverified; `/tmp/*scratch*` evidence paths may be gone.
