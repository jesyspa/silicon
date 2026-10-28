# Fresh-JVM class-data caches, 2026-10-28

For repeated cold CLI invocations, an opt-in JDK cache is useful without changing Silicon. JDK 25 AOT caching was fastest in this sample. The SnaKt suite showed no established improvement. Recommend documenting CLI flags, rather than shipping a default-on launcher or test-build integration.

## Setup and estimator

Silicon `origin/main` at `ae1d99dc45c231c346518a5358922e0bdcaf9667`, Silver `bda9a9da199d688a0543a0f0ab9453701dfa3cc1`. Reused the assembly built for the same-day SnaKt comparison; SHA256 `f12522805d5cc9c3b66b0fa1f8bb24bcf2d4241e9a76c965d52c05ee53a20a43`. A final origin fetch still identified this commit. No Silicon or SnaKt source changes.

Temurin 21.0.10 and 25.0.4; Z3 4.16.0 at `~/data/viper-corpus-results/impact-20261027/z3-4.16.0`. Sequential processes pinned to CPUs 8–15, nice 19, 7 GiB/no-swap scope inside `silicon.slice`, whose aggregate limit was 40 GiB. Another worker used SMT siblings 0–7, so adjacent treatments and uncertainty intervals matter. All accepted runs were daytime, 13:44–14:07 CET. Cold means a fresh JVM, not flushed filesystem caches.

Ratios below one favor the cache/candidate. Intervals are approximate 95% Student-t intervals on paired log ratios: six repeat blocks for CLI, four pairs for SnaKt. These estimate repeat variability on this host, not uncertainty over all Viper programs. No timed repetitions were discarded from the accepted batch.

## Cold CLI

Six existing, previously verified corpus files; six repetitions of four treatments per file (144 fresh JVMs). Treatment order rotates by file/repetition and reverses on alternate repetitions. JVM limits were `-Xmx2g -Xss30m`; no Silicon feature or worker overrides. Every invocation verified successfully.

One disjoint training input was used for both cache types: `Ru1/prusti/verify/pass/nll-rfc/borrow_first.rs/tests_verify_pass_nll-rfc_borrow_first_borrow_first.rs_borrow_first--foo-Both.vpr` (32,573 bytes). Input hashes confirm no overlap with timed files. Training costs below include verification and archive/cache creation, including JDK 25's cache-assembly child process.

| Treatment | Relative to | Paired wall ratio [95% interval] | Cache MiB | Training wall s |
|---|---|---:|---:|---:|
| JDK 21 AppCDS | JDK 21 default | 0.736 [0.584, 0.928] | 63.03 | 4.81 |
| JDK 25 default | JDK 21 default | 1.013 [0.839, 1.223] | — | — |
| JDK 25 AOT | JDK 21 default | 0.587 [0.480, 0.719] | 84.85 | 11.31 |
| JDK 25 AOT | JDK 25 default | 0.580 [0.503, 0.668] | same cache | same training |

The aggregate gives each file equal log weight inside each repeat, then constructs the interval over the six repeat aggregates. Files were selected for size/runtime coverage, not randomly sampled.

| Timed file, relative to `~/data/viper-corpus/` | JDK 21 median s | AppCDS median s | JDK 25 median s | AOT median s | AOT/JDK 25 paired ratio [95% interval] |
|---|---:|---:|---:|---:|---:|
| `Vi/viper/all/chalice/RingBufferRd.vpr` | 3.14 | 2.24 | 3.25 | 1.97 | 0.491 [0.327, 0.738] |
| `PP/mpp/toplas_p3_no_heap/pldi16_so_Time_true.vpr` | 3.64 | 2.67 | 3.58 | 1.84 | 0.585 [0.474, 0.722] |
| `Py/nagini/normal/jetbrains/151-double_the_difference.py.vpr` | 5.02 | 4.08 | 6.21 | 3.34 | 0.574 [0.481, 0.685] |
| `DaG/gobra/dafny_book/chap07.vpr` | 3.36 | 2.46 | 3.48 | 2.00 | 0.562 [0.459, 0.688] |
| `DaG/gobra/dafny_book/chap08.vpr` | 5.11 | 4.76 | 4.65 | 3.24 | 0.719 [0.626, 0.826] |
| `DaG/gobra/dafny_book/chap03.vpr` | 4.17 | 2.59 | 3.49 | 2.11 | 0.571 [0.507, 0.643] |

Median columns and paired ratios are different estimators. The larger intervals include substantial sibling-load/solver variability; the aggregate is not a claim that every file improves.

## SnaKt: full fresh test JVMs

SnaKt `e8d86408298192f121ba835a4f5d87ecf388356d`, copied from `~/data/viper-corpus-results/snakt-20261028/snakt/`; tracked source remained clean afterward. Its local Silicon dependency was substituted with the assembly above through an external Gradle init script. Gradle 8.14.3 ran on JDK 21; its test executable was JDK 25 in both treatments. AOT was selected by the lowest equal-file/equal-repeat CLI geometric mean, before any suite timings.

Training ran an external one-test JUnit fixture that verified the same off-sample Viper input, not a timed SnaKt input. It used the suite's runtime classpath plus a fixture jar. The timed suite did not discover that fixture. Training took 62.22 s command wall, including copied-build recompilation, classpath packaging and cache creation; its one JUnit test took 3.73 s. The resulting cache was 95.32 MiB. This is minimal verifier-oriented training, not comprehensive training of Kotlin compiler paths.

JDK cache creation rejected Gradle's non-empty classpath directories. The init script therefore packed runtime classpath directories into content-addressed jars at task execution, identically for training and both timed treatments (0.20 MiB of packed jars). All nine classpath manifests had identical hashes. An additional untimed, filtered test invocation with `-Xlog:aot=info` confirmed that the AOT cache was opened and its regions mapped (`snakt-smoke/aot.log`); it had the same known test failure. This extra packaging/invalidation machinery is a reason not to ship a test-build integration on these results.

Four adjacent pairs used order baseline/cache, cache/baseline, baseline/cache, cache/baseline. Each command ran `cleanTest test --continue`, offline, without build/configuration caches or a persistent daemon. Each had a fresh test JVM and the project's normal 2 GiB heap, 512 MiB metaspace and 30 MiB stacks.

| Pair | Baseline JUnit sum s | AOT JUnit sum s | Baseline command s | AOT command s |
|---|---:|---:|---:|---:|
| 0 | 51.851 | 53.815 | 59.777 | 61.883 |
| 1 | 74.273 | 53.346 | 98.532 | 60.386 |
| 2 | 52.357 | 72.702 | 62.562 | 86.400 |
| 3 | 62.128 | 78.644 | 86.272 | 87.825 |

- Summed JUnit AOT/baseline: **1.070 [0.672, 1.703]**.
- Full command AOT/baseline: **0.972 [0.568, 1.663]**.
- Every run: 141 passed, one failed, no errors/skips. The identical failure was `User_invariants#testExists`, already present in the pinned-artifact comparison; serialized JUnit failure details matched across all eight runs. Gradle exited 1 solely for that failure.

There is no demonstrated suite gain. These wide intervals also do not establish equivalence. JUnit timing omits JVM startup before test execution; command wall includes it but also Gradle startup and build work. No sbt test benchmark was run; a CLI archive cannot be assumed reusable for sbt or Gradle's different classpaths.

## Enable locally

Use absolute paths and the same JDK build, assembly, classpath order and JVM layout flags for training and use. Regenerate after changing them; keep cache artifacts local rather than distributing a machine-specific binary. The explicit `on` modes below make cache incompatibility fail visibly.

```bash
# JDK 21: train once on a representative off-sample file.
"$JAVA_HOME/bin/java" -Xmx2g -Xss30m \
  -XX:ArchiveClassesAtExit="$PWD/silicon.jsa" \
  -jar "$SILICON_JAR" --z3Exe "$Z3_EXE" "$TRAIN_VPR"
# Subsequent fresh CLI JVMs:
"$JAVA_HOME/bin/java" -Xmx2g -Xss30m -Xshare:on \
  -XX:SharedArchiveFile="$PWD/silicon.jsa" \
  -jar "$SILICON_JAR" --z3Exe "$Z3_EXE" "$INPUT_VPR"

# JDK 25: train and assemble in one command.
"$JAVA_HOME/bin/java" -Xmx2g -Xss30m \
  -XX:AOTCacheOutput="$PWD/silicon.aot" \
  -jar "$SILICON_JAR" --z3Exe "$Z3_EXE" "$TRAIN_VPR"
"$JAVA_HOME/bin/java" -Xmx2g -Xss30m \
  -XX:AOTCache="$PWD/silicon.aot" -XX:AOTMode=on \
  -jar "$SILICON_JAR" --z3Exe "$Z3_EXE" "$INPUT_VPR"
```

A launcher can expose an opt-in environment variable without changing Silicon:

```bash
# Bash; JAVA_HOME identifies JDK 25 for this example.
cache_args=()
if [[ -n ${SILICON_AOT_CACHE:-} ]]; then
  cache_args=("-XX:AOTCache=$SILICON_AOT_CACHE" -XX:AOTMode=on)
fi
exec "$JAVA_HOME/bin/java" -Xmx2g -Xss30m "${cache_args[@]}" \
  -jar "$SILICON_JAR" "$@"
```

For example, set `SILICON_AOT_CACHE=/absolute/path/silicon.aot` after training. For JDK 21, use `-Xshare:on -XX:SharedArchiveFile=/absolute/path/silicon.jsa` instead. Pass these as arguments to the target test JVM, not globally through `JAVA_TOOL_OPTIONS`, which also affects build/launcher JVMs. A Gradle `Test.jvmArgumentProviders` provider preserves project-assigned `jvmArgs`; the benchmark init script records a complete example, including classpath packing.

## Raw evidence

`~/data/viper-corpus-results/fresh-jvm-cache-20261028/` contains `cli.py`, `snakt-run.py`, `bench.init.gradle.kts`, the external training fixture, `cli.jsonl`, input hashes, cache files, per-process CLI logs, per-run Gradle/JUnit/GC logs, identical classpath manifests and `summary.json`. Run `python3 analyze.py` there to regenerate the estimates. `COMMIT` identifies the reused assembly build.

`rejected-parser-pilot/`, `rejected-overlap/` and `rejected-snakt-directory-classpath/` are explicitly excluded setup attempts: old-syntax parser failures, a cancelled background scope that survived its wrapper, and the rejected directory classpath respectively. All accepted CLI measurements were restarted in a clean output set after both overlapping scopes were terminated.
