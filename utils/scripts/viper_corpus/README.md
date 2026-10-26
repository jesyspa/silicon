# Viper corpus benchmarks

Use this harness to curate an external `.vpr` corpus and compare Silicon JARs using cgroup-v2 solver CPU measurements.

See [harness.md](../../../harness.md) for delegated cgroup setup, commands, measurement semantics, resume behavior, and tests.

- `curate.py`: deduplicated JSONL manifest and deterministic stratified quick subset.
- `run.py`: parallel, resumable runs through `../solver_cpu_benchmark.py`.
- `WarmRunner.java`: long-lived Silicon driver for `run.py --warm`.
- `compare.py`: per-group timing ratios, outcome transitions, and ranked changes.
- `night.py`: several arms under several Z3 seeds, interleaved per file, in warm lanes. With `--profile`, seeds per file follow its noise class; with `--baseline`, other arms get a timeout of 4x the baseline's slowest run and cut runs are recorded as `capped`.
- `analyze.py`: seed-median ratios with bootstrap CIs, instability, capped regressions, outcome tables, and GC share for `night.py` runs.
- `noise.py`: per-file noise profile (spread across seeds per JAR, class) from `night.py` runs.
- `tiers.py`: T0 corpus root and manifest from a case list, T1 manifest from a profile, relevance tags, tag-filtered samples, and projected lane-hours.

Warm runners (`run.py --warm`, `night.py`) default to `-Xss512m`, `-XX:G1PeriodicGCInterval=10000` (an idle runner returns its heap) and `--numberOfParallelVerifiers 1` unless the arguments set them.

Runner scopes go in the user slice `--slice` (default `silicon.slice`). Its `MemoryMax` bounds all Silicon work on the host together; set it once with `systemctl --user set-property silicon.slice MemoryMax=40G MemorySwapMax=0`. Run other Silicon jobs (builds, test suites) in the same slice with `systemd-run --user --scope --slice=silicon.slice`. `night.py` keeps one runner per arm in every lane, so size `-Xmx`, `memory_max_size` and the lane count to fit: about lanes x (heap + Z3 limit) + idle runners x 0.4 GB.

Each script has `--help`. Keep corpus files, manifests, and run output outside the repository.

## Warm-runner failures

`run.py::Lane.request` bounds request writes and response reads by one deadline.
It discards the runner after a timeout, incomplete or malformed response, fatal
error, or observed OOM kill. Protocol diagnostics go to `runner-<label>.log`.
Request fields must contain no tabs or newlines; these delimit the wire protocol.

`night.py` applies the same transaction boundary to warm-ups. A transport or
verification failure discards the runner and retries warm-up once in a fresh process. A second
failure retires that CPU lane and returns its unmeasured task to the queue for
other lanes. `night.log` records dead lanes; runner logs record failed attempts.
If all lanes die with tasks remaining, the command exits nonzero. Completed
results remain resumable. Inspect the warm-up and runner logs before resuming.

The harness guards in `test_viper_corpus.py` use fake runners and mocked pipe IO;
they do not invoke Silicon or Z3. Run them when testing is permitted:

```sh
python3 -m unittest discover -s utils/scripts/viper_corpus -p 'test_viper_corpus.py'
```
