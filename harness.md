# Viper corpus benchmark harness

Use `utils/scripts/viper_corpus/` to curate an external Viper corpus, measure a Silicon JAR, and compare runs. The harness uses Python's standard library and Linux cgroup v2. Keep the corpus outside this repository, including its import directories. Build the JAR separately and provide executable Java and Z3 paths.

## Curate

Write manifests and results outside the repository:

```sh
CORPUS="$HOME/data/viper-corpus"
BENCH="$HOME/data/viper-bench"
mkdir -p "$BENCH"
python3 utils/scripts/viper_corpus/curate.py \
  --corpus-root "$CORPUS" \
  --manifest "$BENCH/full.jsonl" --quick "$BENCH/quick.jsonl"
```

Each JSON line contains `path` (corpus-relative POSIX path), `group` (first path component), `sha256`, and `size` (bytes). Files below any `_preambles/` directory are excluded before deduplication. SHA-256 duplicates retain the lexicographically first corpus-relative path across all groups.

The quick subset reserves capped coverage in each group, keeping every file of groups below the cap. It puts the largest files in each group's reserved selection, samples the rest with a fixed seed, then fills remaining slots round-robin across groups. See `curate.py --help` for size, cap, seed, and largest-file controls. A target smaller than reserved coverage is rejected. A corpus smaller than the target is included in full. The manifests contain metadata only; imported preambles remain available in the external corpus.

For the supplied corpus, curation produces 4,009 unique eligible files from 4,858 inputs: 25 preamble files are excluded and 824 remaining copies are duplicates. The quick manifest contains 250 files spanning all 19 groups.

## Obtain a delegated cgroup root

On a systemd host with a user manager, start a delegated scope from the repository directory:

```sh
systemd-run --user --scope -p Delegate=yes bash
```

Inside that shell, derive its cgroup root:

```sh
export CGROUP_ROOT="/sys/fs/cgroup$(awk -F: '$1 == "0" {print $3}' /proc/self/cgroup)"
cat "$CGROUP_ROOT/cgroup.controllers"
```

Run the harness in this shell and leave it open until the run finishes. The scope must allow child-directory creation, writing child `cgroup.procs` and `cgroup.kill`, and reading child `cpu.stat`. CPU usage accounting does not require enabling the CPU controller in `cgroup.subtree_control`. If delegation is restricted by local policy, ask the administrator for a writable delegated cgroup-v2 subtree with these capabilities. An ordinary directory cannot substitute for a cgroup.

The measurement code creates independent solver and verifier leaves for every input. Java enters the verifier leaf before execution; the existing Z3 wrapper moves each solver into the solver leaf. Total process CPU is the sum of both leaves, including descendants, even after timeout termination. It excludes the Python scheduler, version probes, and artifact hashing. Solver CPU counts only the solver leaf. Both leaves are terminated, accounted, and removed by `utils/scripts/solver_cpu_benchmark.py`.

## Run and resume

From the delegated shell:

```sh
CORPUS="$HOME/data/viper-corpus"
BENCH="$HOME/data/viper-bench"
python3 utils/scripts/viper_corpus/run.py \
  --corpus-root "$CORPUS" --manifest "$BENCH/quick.jsonl" \
  --jar /absolute/path/to/silicon.jar \
  --java /absolute/path/to/java --z3 /absolute/path/to/z3 \
  --cgroup-root "$CGROUP_ROOT" --output-directory "$BENCH/baseline-quick" \
  --jobs 6 --timeout 300 --jvm-arg=-Xmx6g
```

Use `run.py --help` for defaults. Budget JVM heap and solver memory across all jobs. Repeat `--jvm-arg` and `--verifier-arg` for individual tokens, using `=` for dash-prefixed values, for example `--verifier-arg=--numberOfParallelVerifiers --verifier-arg=1`. The reused benchmark rejects options that would override its accounting, timeout, or temporary-directory configuration. The harness measures Z3, not other solver backends. `--seed N` sets Z3's `smt.random_seed` and `sat.random_seed` to N. Silicon accepts one `--proverArgs` string, so the seed joins an existing `--proverArgs` verifier argument or adds one; seed 0 is Z3's default. Results and `run.json` record the seed.

Each scheduled input invokes the existing benchmark CLI in its own process. This keeps signal handling and subprocess accounting separate between parallel jobs. The timeout covers JVM startup and verification; version probes and hashing happen first. `wall_s` is the benchmark's verifier launch-to-shutdown interval, not scheduler queue time or metadata-probe time.

`results.jsonl` is appended and synced after each completed file. Records contain the manifest metadata, `outcome`, `solver_cpu_s`, `total_process_cpu_s`, `wall_s`, return code, errors, and a relative measurement path. Detailed counters, commands, versions, artifact hashes, and stdout/stderr remain under `inputs/<hash>-<attempt-id>/`.

Outcomes:

- `verified`: successful verifier exit without measurement or cleanup errors.
- `failed`: nonzero verifier exit with a recognized ordinary verification-error summary, including negative tests and frontend errors.
- `timeout`: external wall timeout or a recognized verifier timeout summary.
- `crash`: unexpected exit, internal exception, missing measurement, or accounting/cleanup error.

Summary matching targets Silicon text output. Inspect `stdout.log` for unexpected `crash` results when using a JAR with different reporting. Missing measurements have null metrics rather than fabricated zero CPU.

Repeat the same command to resume. The harness validates every input's size and digest, locks the output directory against concurrent writers, and checks `run.json` against the manifest, corpus location, software hashes, and options. Changing concurrency or the delegated cgroup root is allowed; changing the JAR, input manifest, or verifier/JVM options requires a new output directory. All recorded outcomes, including crashes and timeouts, count as complete. To retry them, curate a smaller manifest and use a fresh directory.

After an interruption, an incomplete final JSONL line is truncated and rerun. Complete malformed records are rejected. Attempts without a committed results line are rerun into new directories; old logs remain for inspection. Prefer Ctrl-C or SIGTERM to the benchmark processes for cleanup. SIGKILL cannot run cleanup; inspect the delegated subtree and terminate leftover processes before resuming.

## Warm JVM mode

A cold JVM spends much of a small file's CPU on JIT compilation. `--warm` instead keeps one long-lived `WarmRunner` JVM per job. Each input gets a fresh Silicon frontend in that JVM, limited by Silicon's own `--timeout`. Omit `--cgroup-root`. Each runner gets its own `systemd-run --user` scope with `MemoryMax=--lane-memory-max` (default `7G`), `MemorySwapMax=0`, and `OOMPolicy=continue`, so one runaway Z3 cannot starve the other lanes. Budget `--jobs` times the lane limit. Pin CPUs by running the harness under `taskset`:

```sh
taskset -c 2,3,10,11 python3 utils/scripts/viper_corpus/run.py \
  --corpus-root "$CORPUS" --manifest "$BENCH/quick.jsonl" \
  --jar /absolute/path/to/silicon.jar --java /absolute/path/to/java --z3 /absolute/path/to/z3 \
  --output-directory "$BENCH/warm-quick" --jobs 2 --timeout 300 --jvm-arg=-Xmx4g \
  --lane-memory-max 6G \
  --warm --warm-runs 2
```

`run.py` compiles `WarmRunner.java` with the `javac` and `jar` next to `--java` into `runner.jar` in the output directory and runs `java -cp runner.jar:silicon.jar WarmRunner`. The runner uses only the public frontend API, so it works with older Silicon JARs. Each request is a stdin line: a log path, then Silicon arguments, separated by tabs. Each response is one JSON line with `success`, `wall_s`, JVM CPU ticks, reaped-children CPU ticks, heap after GC, per-collector GC deltas, and `fatal`.

Results add these fields:

- `solver_cpu_s`: CPU of reaped child processes, which is Z3. It is the `cutime`+`cstime` change in `/proc/self/stat` across the request.
- `jvm_cpu_s`: the runner's own CPU, which includes JIT and GC threads.
- `total_process_cpu_s`: the sum of `solver_cpu_s` and `jvm_cpu_s`.
- `heap_after_gc_bytes`: used heap after `System.gc()`.
- `gc`: per-collector `count` and `ms` from `GarbageCollectorMXBean` across the request, excluding the `System.gc()` after it. `gc_s` sums the collectors whose name lacks `Concurrent`; for G1 that is pause time. Runners built from an older `WarmRunner.java` omit both.
- `lane`, `runner_start`, `runner_served`, `after_restart`: the job slot, its runner start count, the number of files this runner verified before, and whether the runner has been restarted.
- `warmup`: the same fields for earlier `--warm-runs` verifications. The top-level fields describe the last run.

The JVM runs with `-XX:+ExitOnOutOfMemoryError`. The harness restarts a runner after a fatal `Error`, after an OOM kill in its scope, and after it dies. It also restarts a runner that gives no answer within the timeout plus 30 s, killing it with its process group first. A file's outcome is `oom` when the scope's `memory.events` `oom_kill` count rises during the request. This is usually Z3, and the runner survives it. The outcome is also `oom` when the runner exits with status 3 (heap exhaustion) or is killed by a SIGKILL the harness did not send. In that last case the kernel chose the JVM, and its scope is already gone. A runner with no answer gives `timeout`, and other deaths give `crash`. Other outcomes come from the log as in cold mode.

Results stay comparable with `compare.py`. Warm and cold outcomes can differ where stack depth matters. JIT-compiled frames are smaller, so a file that overflows the stack in a cold JVM can verify in a warm one. Solver memory limits need cgroups and are rejected with `--warm`.

## Seeded multi-arm nights

`night.py` compares several JARs or option sets ("arms") under several Z3 seeds in one warm run. Each `--arm` is `NAME=JAR` followed by extra Silicon arguments; arguments prefixed with `-J` go to the JVM instead. `--seeds` lists the Z3 seeds (default `0,1,2`), merged into `--proverArgs` as with `run.py --seed`. Common `--jvm-arg` and `--verifier-arg` options apply to every arm.

```sh
python3 utils/scripts/viper_corpus/night.py \
  --corpus-root "$CORPUS" --manifest "$BENCH/quick.jsonl" --output-directory "$BENCH/night" \
  --java /absolute/path/to/java --z3 /absolute/path/to/z3 \
  --arm "M=/jars/main/silicon.jar" --arm "W=/jars/candidate/silicon.jar --assertTimeout 10000" \
  --lanes '2,10;3,11;6,14' --jvm-arg=-Xmx4g --jvm-arg=-Xss128m \
  --verifier-arg=--proverArgs --verifier-arg=memory_max_size=3000 \
  --warmup DaG/gobra/dafny_book/chap08.vpr
```

Files run in a shuffled order. Every arm and seed of a file runs, in shuffled order, before the next file starts, so a run stopped early leaves every arm and seed with the same files and spreads load drift over them. Repeated `--manifest` options run in order, so a priority subset can go first. `--order-seed` fixes both shuffles.

Each `--lanes` entry is a CPU set. A lane holds one warm runner per arm, started on first use, each in its own `--lane-memory-max` scope and pinned with `taskset`. Restarts and outcomes follow warm mode. `--warmup` inputs are verified, unrecorded, after every runner start. Each runner writes `-Xlog:gc` to `gc/<arm>-lane<N>-<pid>.log`.

`results.jsonl` holds one record per file, arm and seed, with the warm-mode fields plus `arm`, `seed` and `cpus`. Repeat the command to resume; a changed configuration other than `--lanes` and `--window` is refused. Touch `STOP` in the output directory to stop after the running tasks. Lanes start tasks only inside `--window` (local time, default `19:00-08:00`; `any` disables it) and stop at its end.

`analyze.py` reports a night against a baseline arm:

```sh
python3 utils/scripts/viper_corpus/analyze.py "$BENCH/night" --baseline M \
  --subset seq=targeted.txt --json "$BENCH/night/analysis.json" > "$BENCH/night/analysis.md"
```

Per file and arm it takes the median across seeds. A file is unstable when max/min across seeds exceeds `--unstable` (default 2) in any arm. A file enters a comparison only when every seed of both arms has the same `verified` or `failed` outcome and a positive metric. For stable files it reports the geometric mean ratio (arm / baseline) with a 95% percentile bootstrap CI over files, and the number of files. Unstable files, outcome tables per arm and seed, outcome changes across seeds within an arm, and outcome changes across arms at one seed are listed separately. GC pause share is the summed non-`System.gc()` pause time in the `-Xlog:gc` logs over the summed request wall time, warm-ups included (from `warmup/wall.jsonl`), per arm; the per-request MXBean share is shown beside it. `--subset NAME=FILE` repeats the ratios for the corpus paths listed in FILE.

## Compare

Run the candidate JAR into another output directory with the same manifest and options, then:

```sh
python3 utils/scripts/viper_corpus/compare.py \
  "$BENCH/baseline-quick" "$BENCH/candidate-quick" --top 10 \
  > "$BENCH/comparison.json"
```

The JSON report groups comparisons by corpus group. Ratios are candidate / baseline, so values above one are regressions. Each metric includes its geometric mean ratio, eligible/excluded pair counts, and ranked regressions and improvements. Outcome transitions are reported both per group and by path. Unmatched inputs and changed input digests/groups are listed separately.

Timing comparisons use matched paths and digests with the same `verified` or `failed` outcome and finite positive measurements. Timeouts, crashes, changed outcomes, nulls, and zeros are excluded, not treated as successful fast runs. An empty metric has a null mean. Use outcome changes alongside timing ratios; the harness does not infer statistical significance from a single run.

## Tests

```sh
python3 -m unittest discover -s utils/scripts -p test_solver_cpu_benchmark.py
python3 -m unittest discover -s utils/scripts/viper_corpus -p 'test_*.py'
```

The solver test suite also has a real-cgroup test, skipped unless `VIPER_TEST_CGROUP_ROOT` is set. In the delegated shell, run:

```sh
VIPER_TEST_CGROUP_ROOT="$CGROUP_ROOT" \
  python3 -m unittest discover -s utils/scripts -p test_solver_cpu_benchmark.py
```

It uses small fake Java/Z3 executables to verify cgroup placement, solver and total CPU, timeout termination, and cleanup. It does not require a built Silicon JAR or corpus access.
