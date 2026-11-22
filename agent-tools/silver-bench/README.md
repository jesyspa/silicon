# Silver frontend benchmark

Measures how much each change in a Silver commit series speeds up the Silver frontend. For each file,
`SilverBench` runs parsing, semantic analysis, translation, consistency checking and the plugin work before
verification (with a no-op verifier), then the Silver utilities a backend calls per program. It records thread CPU
time, wall time, allocated bytes and GC time for each phase as one JSON line per file.

Each build of Silver is an "arm": `FULL` is the whole series, `BASE` is upstream, and every `cNN-*` arm is `FULL`
with one change left out. Comparing an arm with `FULL` shows what that one change saves.

## Setup

All scripts read `BENCH_DIR`, the work directory for the arms, lists, runs and profiles. `BENCH_CORPUS` points at
the Viper corpus (default `~/data/viper-corpus`). Run the scripts in place; they find the harness next to themselves.

```sh
export BENCH_DIR=$(mktemp -d /tmp/bot-run.XXXXXXXXXX)
./build-arms.sh ~/dev/silicon/wip3/silver            # [FULL_REV [BASE_REV]]
(cd harness && sbt -batch compile)                   # needs $BENCH_DIR/cp-full.txt from build-arms.sh
./make-lists.py                                      # subset.txt, warm300.txt
```

- `build-arms.sh` adds a detached worktree of the given Silver repository at `$BENCH_DIR/silver-arms`. It then
  builds every arm into `$BENCH_DIR/arms/NAME/classes` and exports the FULL classpath (`cp-full.txt`, `deps.txt`,
  `deps-shared.txt`, `reflect-jar.txt`). Each arm's `diffstat.txt` shows how it differs from FULL. The arm
  definitions hard-code commits of the frontend-speedups series (`upstream/frontend-4-whole-program-passes`).
  To benchmark another series, edit them.
- `make-lists.py` writes `subset.txt`: every corpus file outside `Ru2/`, plus 400 `Ru2/` files picked with seed 1
  (1259 files). It also writes `warm300.txt`, 300 files of the subset used only to warm up the JIT.

## Paired comparison (recommended)

```sh
./after.sh          # profiles BASE and FULL with JFR, then pairs FULL with every other arm
./pairs.py          # summary table
```

`run-pair.sh ARM CPU OUT` loads FULL and ARM into separate class loaders of one JVM (`launcher/PairedLauncher.java`,
compiled into `$BENCH_DIR/launcher` on first use). It warms both up on `warm300.txt`, then runs both on every file
of `subset.txt` back to back, in a random order per file. Drift in machine speed therefore hits both arms alike.
`after.sh` spreads the arms over the two CPUs in `BENCH_CPUS` (default `6 7`) and logs to `$BENCH_DIR/runs/pair.log`.
A full round takes several hours.

In the `pairs.py` output, each row is one arm (the change left out) against FULL:

- `cpu ratio`: summed CPU time of the arm divided by FULL's, with a 95% bootstrap interval over files. Above 1
  means the change saves time. An interval that excludes 1 is a real effect.
- `saved s`: CPU seconds the change saves over the subset. `alloc`: allocation ratio. `FULL s`: FULL's total.
- `main phase`: the phase whose CPU time changes most.
- `STATUS-DIFF n`: on n files, the arm and FULL reached different results (errors or exceptions). A pure
  optimisation must show none.

For profiles, run `./profsum.py $BENCH_DIR/prof/FULL.jfr 10`. It prints the share of samples per phase, the hottest
Silver methods per phase, and inclusive shares of notable subsystems. To parse the recording it uses `jfrstacks.py`
(`$JFRSTACKS`, default `~/data/viper-corpus-results/lazy-optional-20261120/bin/jfrstacks.py`).

## Separate-JVM rounds (older method)

`sched.py ROUNDS FIRST_ROUND` runs every arm once per round through `run-arm.sh`, one JVM per arm, on two pinned
CPUs. It adds FULL control runs in each lane. `analyse.py` then prints the BASE/FULL tables per phase and the
leave-one-out ratios, with a bootstrap over both runs and files, plus the FULL control totals. CPU time drifted by
about ±10% from one JVM run to the next, more than most single changes save. The paired launcher replaced this
method for that reason; allocation is steadier across runs than CPU time.

`run-arm.sh ARM CPU LIST OUT WARM PASSES [WARMLIST [JVM args]]` also works on its own, for example for a quick check
of one arm. Set `BENCH_DEBUG=1` to print stack traces for files that end in an exception under the paired launcher.
