# Viper corpus benchmarks

Use this harness to curate an external `.vpr` corpus and compare Silicon JARs using cgroup-v2 solver CPU measurements.

See [harness.md](../../../harness.md) for delegated cgroup setup, commands, measurement semantics, resume behavior, and tests.

- `curate.py`: deduplicated JSONL manifest and deterministic stratified quick subset.
- `run.py`: parallel, resumable runs through `../solver_cpu_benchmark.py`.
- `WarmRunner.java`: long-lived Silicon driver for `run.py --warm`.
- `compare.py`: per-group timing ratios, outcome transitions, and ranked changes.
- `night.py`: several arms under several Z3 seeds, interleaved per file, in warm lanes.
- `analyze.py`: seed-median ratios with bootstrap CIs, instability, outcome tables, and GC share for `night.py` runs.

Each script has `--help`. Keep corpus files, manifests, and run output outside the repository.
