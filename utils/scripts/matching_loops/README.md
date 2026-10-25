# Matching-loop analysis

`mloop.py` runs Silicon on one `.vpr` file, finds its slowest Z3 queries, and checks each one for a matching loop.

```sh
python3 mloop.py FILE.vpr --jar silicon.jar --out OUT \
  --java ~/.sdkman/candidates/java/21.0.10-tem/bin/java --z3 ~/opt/z3-4.16.0-x64-glibc-2.39/bin/z3 \
  --cpus 4,12 --seeds 1,2
```

The result is `OUT/report.md`, with the same data in `OUT/report.json`.
To analyse a finished run again without rerunning Silicon, add `--reuse`.
Use `--help` to see all options.

## Steps

1. Run Silicon with `--z3Exe z3tap.py`. The tap passes everything through to Z3 and records it:
   - `tap/<pid>.in`: the exact Z3 input, with a `;@T <time>` line before each check-sat
   - `tap/<pid>.out`: every Z3 output line, from stdout and stderr, with its time. Z3 reports running out of memory on stderr, and Silicon reads the prover's stderr together with its stdout.
   - `tap/<pid>.args`: the Z3 command line

   This is the prover log. It records what Z3 received, including Silicon's restarts and parallel provers.
   Silicon gets `memory_max_size` (`--memory-mb`, default 3000) and `--seed` through `--proverArgs`.
2. Time each check-sat from its `;@T` line to its answer. A Z3 out-of-memory error counts as the answer. A query without an answer ran until the process ended. `queries.tsv` lists every query.
3. Take the `--top` slowest queries that took at least `--min-seconds`. For each one, cut a prefix that ends at its check-sat, in `q-<pid>-<index>/`:
   - `prefix.smt2` drops the earlier check-sats, get-info and get-model commands, and Silicon's time and rlimit limits.
   - If that replay takes less than 30% of the original time (and less than 90% of `--replay-timeout`), `prefix-history.smt2` is cut as well. It keeps the earlier check-sats. Some queries depend on Z3's search history, so they are slow only with it.
     Earlier queries that took at least 5 s are capped at twice their time, at most 10 s. Faster ones get no cap, because any `(set-option :timeout ...)` changes Z3's search. On timsort1, timeouts of 1 s or more on fast earlier queries turned an out-of-memory query into an instant unsat.
4. Replay with `smt.qi.profile=true` and statistics. The final check-sat gets `--replay-timeout` (default 60 s) as a soft timeout, so Z3 still prints its profile.
   - The preamble's `smt.qi.eager_threshold` is removed and passed on the command line.
   - A history replay also runs `prefix-history-pre.smt2`, which stops before the final check-sat. It subtracts that profile so that the counts belong to the final query.
5. If max generation reaches half the eager threshold, replay again at each `--eager` threshold (default 10 and 30). With `--seeds`, replay under each extra seed.

## Report

The header gives the time in check-sats, summed over Z3 processes. If Z3 printed nothing in the last 10 s or more of the run, it says so: Silicon spent that time outside the prover, and no query explains it.

For each query, the report gives:

- the original time and result, and the replay's time, result, instances, max generation and memory
- the top quantifiers by instances, with their max generation, and the body terms that can match their own trigger
- trigger cycles among the hot quantifiers: strongly connected components of the "body term matches trigger head" graph
- where each quantifier comes from:
  - user quantifiers by `file:line:col`
  - domain axioms by name, at their line in the file or its imports
  - Silicon's quantified-permission and function axioms by kind, with the Viper function they belong to
- with `--seeds`, each seed's replay, and `seed-sensitive` if the max/min time ratio exceeds 2 or the results differ

The verdict is one of these:

- `loop`: max generation reaches 80% of the threshold at every eager threshold, and total instances grow with the threshold.
  The culprits are the quantifiers whose instances grow most in absolute terms. To count, a quantifier must grow at least 1.5x and reach half the highest threshold in generation.
  Quantifiers on a trigger cycle rank first. The others, such as `Seq_add_def`, only consume the terms that the cycle creates.
  The quantifier with the largest growth is always listed, even when it is not on a cycle. On pair_insertion_sort that is the body of Gobra's `sorted` function, which consumes the terms that the inverse-function and Seq index axioms create.
- `possible loop`: max generation reaches 80% of the threshold only at the base threshold, or a hot quantifier feeds its own trigger and reaches half the threshold.
- `no loop`: neither.

## Caveats

- Z3 reports a quantifier when its scope is popped, and the rest at exit. Silicon asserts quantifiers with the same qid in many scopes, so the counts are summed per qid.
- When an earlier query in a history replay hits its time cap, the replay and its baseline can diverge. The report then warns that the per-quantifier counts are approximate.
- The tap adds a Python process per Z3 process, and its cost is small compared with Z3. It flushes its input log at every check-sat, so a SIGKILL loses nothing that a query needs.
- `--cpus` pins Silicon and the replays. Replays run in parallel, one per listed CPU. While a corpus night runs, use at most 2 CPUs away from the lane CPUs.

## Test

```sh
python3 -m unittest test_mloop
```

The tests need Z3 for the tap and end-to-end cases. They look for `$MLOOP_Z3`, then `z3` on `PATH`, then `~/opt/z3-4.16.0-x64-glibc-2.39/bin/z3`.
