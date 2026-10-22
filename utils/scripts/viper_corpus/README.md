# Viper corpus benchmarks

Use this harness to curate an external `.vpr` corpus and compare Silicon JARs using cgroup-v2 solver CPU measurements.

See [harness.md](../../../harness.md) for delegated cgroup setup, commands, measurement semantics, resume behavior, and tests.

- `curate.py`: deduplicated JSONL manifest and deterministic stratified quick subset.
- `run.py`: parallel, resumable runs through `../solver_cpu_benchmark.py`.
- `WarmRunner.java`: long-lived Silicon driver for `run.py --warm`.
- `compare.py`: per-group timing ratios, outcome transitions, and ranked changes.

Each script has `--help`. Keep corpus files, manifests, and run output outside the repository.
