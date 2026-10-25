#!/usr/bin/env python3
"""Find the slowest Z3 queries of a Silicon run and check them for matching loops.

Steps: run Silicon with a Z3 tap (prover log with per-query timing), pick the slowest
queries, cut a replayable prefix for each, replay it with smt.qi.profile and statistics,
sweep smt.qi.eager_threshold, optionally sweep seeds, and write report.md and report.json.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

import smtq

HERE = Path(__file__).resolve().parent
RESULTS = ('sat', 'unsat', 'unknown')
DROPPED_HISTORY = ('(check-sat', '(get-model', '(get-info', '(get-value', '(get-unsat-core', '(eval ')
LIMITS = ('(set-option :timeout', '(set-option :rlimit')
EAGER = '(set-option :smt.qi.eager_threshold'


def seed_args(seed):
    return [f'smt.random_seed={seed}', f'sat.random_seed={seed}']


def run_silicon(args, out):
    """Runs Silicon once with every Z3 process going through z3tap.py."""
    tap = out / 'tap'
    shutil.rmtree(tap, ignore_errors=True)
    tap.mkdir(parents=True)
    prover = [f'memory_max_size={args.memory_mb}'] + seed_args(args.seed) + args.prover_arg
    command = ['taskset', '-c', args.cpus, str(args.java), '-Xmx4g', '-Xss128m', *args.jvm_arg,
               '-jar', str(args.jar), '--z3Exe', str(HERE / 'z3tap.py'),
               '--proverArgs', ' '.join(prover), *args.silicon_arg, str(args.vpr)]
    env = dict(os.environ, MLOOP_Z3=str(args.z3), MLOOP_TAP_DIR=str(tap))
    start = time.time()
    with open(out / 'silicon.log', 'w') as log:
        log.write(' '.join(command) + '\n')
        log.flush()
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env,
                                   start_new_session=True)
        try:
            code = process.wait(args.timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            code = 'timeout'
    end = time.time()
    run = dict(code=code, wall_s=round(end - start, 2), end=end, jar=str(args.jar), seed=args.seed)
    (out / 'run.json').write_text(json.dumps(run))
    return run


def queries(tap, end=None):
    """Per-query records from a tap directory: process, index, start, seconds, result."""
    records = []
    for path in sorted(tap.glob('*.in')):
        responses = []
        out = path.with_suffix('.out')
        if out.exists():
            with open(out, errors='replace') as f:
                for line in f:
                    stamp, _, text = line.partition(' ')
                    if text.strip() in RESULTS:
                        responses.append((float(stamp), text.strip()))
                    elif text.startswith('(error') and 'out of memory' in text:
                        responses.append((float(stamp), 'out of memory'))
        stamp = closed = None
        timeout = rlimit = None
        unanswered = []
        index = 0
        cursor = 0
        with open(path, errors='replace') as f:
            for number, line in enumerate(f, 1):
                if line.startswith(';@T '):
                    stamp = float(line[4:])
                elif line.startswith(';@E '):
                    closed = float(line[4:])
                elif line.startswith('(set-option :timeout'):
                    timeout = int(line.split()[-1].rstrip(')'))
                elif line.startswith('(set-option :rlimit'):
                    rlimit = int(line.split()[-1].rstrip(')'))
                elif line.startswith('(check-sat'):
                    while cursor < len(responses) and responses[cursor][0] < stamp:
                        cursor += 1
                    record = dict(process=path.stem, index=index, line=number, start=stamp,
                                  timeout_ms=timeout, rlimit=rlimit)
                    if cursor < len(responses):
                        record.update(seconds=responses[cursor][0] - stamp, result=responses[cursor][1])
                        cursor += 1
                    else:
                        record.update(result='none')
                        unanswered.append(record)
                    records.append(record)
                    index += 1
        for record in unanswered:
            finish = closed or end or record['start']
            record['seconds'] = max(finish - record['start'], 0)
    return records


def last_output(tap):
    """Unix time of the last Z3 output line in any process, or None."""
    stamps = []
    for path in tap.glob('*.out'):
        lines = path.read_text(errors='replace').splitlines()
        if lines:
            stamps.append(float(lines[-1].partition(' ')[0]))
    return max(stamps, default=None)


def cut(tap_in, line, destination, history, timeout_ms, final=True, caps=None):
    """Writes the input up to the check-sat on `line` as a standalone replay script.

    With history, each earlier check-sat on line n gets the timeout caps[n] in ms, so an
    earlier slow query cannot dominate the replay. With final=False the script stops just before that check-sat; its profile is the
    baseline that a history replay subtracts.
    """
    with open(tap_in, errors='replace') as f, open(destination, 'w') as out:
        for number, text in enumerate(f, 1):
            if text.startswith(';@') or text.startswith(EAGER):
                continue
            if number == line:
                if history:
                    out.write('(get-info :all-statistics)\n')
                if final:
                    out.write(f'(set-option :timeout {timeout_ms})\n')
                    out.write(text)
                    out.write('(get-info :reason-unknown)\n(get-info :all-statistics)\n')
                out.write('(exit)\n')
                return
            if not history and (text.startswith(DROPPED_HISTORY) or text.startswith(LIMITS)):
                continue
            if history and caps and number in caps:
                out.write(f'(set-option :timeout {caps[number]})\n')
            if text.startswith('(exit'):
                continue
            out.write(text)
    raise ValueError(f'{tap_in} has no line {line}')


def z3_params(tap_args):
    """Z3 parameters from the original command line, without I/O flags or time limits."""
    return [a for a in tap_args if '=' in a and not a.startswith('-T')]


def override(params, extra):
    keys = {p.split('=', 1)[0] for p in extra}
    return [p for p in params if p.split('=', 1)[0] not in keys] + extra


def replay(args, script, params, label, baseline=None):
    """Runs Z3 on script and returns the parsed result, statistics and profile.

    With a baseline script (the same prefix without the final check-sat), its profile is
    subtracted, so a history replay reports only the final query's instances.
    """
    if baseline is not None:
        before = run_z3(args, baseline, params, label + '-pre')['profile']
        result = run_z3(args, script, params, label)
        excess = 0
        for qid, (instances, _, _) in before.items():
            after = result['profile'].get(qid, [0, 0, 0.0])
            excess += max(instances - after[0], 0)
            if qid in result['profile']:
                after[0] = max(after[0] - instances, 0)
        # Earlier queries capped by wall time can run differently in the two replays.
        result['baseline_mismatch'] = excess > 0.05 * max(sum(v[0] for v in before.values()), 1)
        return result
    return run_z3(args, script, params, label)


def run_z3(args, script, params, label):
    stdout = script.with_name(label + '.out')
    stderr = script.with_name(label + '.err')
    hard = args.replay_timeout + 60
    command = ['taskset', '-c', args.cpus, str(args.z3), '-smt2', f'-T:{hard}', *params,
               'smt.qi.profile=true', str(script)]
    start = time.monotonic()
    with open(stdout, 'w') as o, open(stderr, 'w') as e:
        code = subprocess.call(command, stdout=o, stderr=e)
    wall = time.monotonic() - start
    return dict(label=label, wall_s=round(wall, 2), code=code, params=params, baseline_mismatch=False,
                **parse_output(stdout.read_text(errors='replace')),
                profile=parse_profile(stdout, stderr))


STAT = re.compile(r'[( ]:([a-z][a-z0-9-]*)\s+([0-9.]+)')


def parse_output(text):
    result = None
    reason = None
    blocks = []
    current = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped in RESULTS or stripped == 'timeout':
            result = stripped
        elif stripped.startswith('(:reason-unknown'):
            reason = stripped[len('(:reason-unknown'):].strip(' )').strip('"')
        elif stripped.startswith('(:') and current is None:
            current = {}
        if current is not None:
            for key, value in STAT.findall(line):
                current[key] = float(value)
            if stripped.endswith(')') and not stripped.startswith('(:reason'):
                blocks.append(current)
                current = None
    if current:
        blocks.append(current)
    statistics = blocks[-1] if blocks else {}
    if len(blocks) > 1:
        before = blocks[-2]
        for key in ('quant-instantiations', 'conflicts', 'decisions'):
            if key in statistics:
                statistics[key] -= before.get(key, 0)
    return dict(result=result, reason_unknown=reason, statistics=statistics)


def parse_profile(*paths):
    """Per qid: instances, max generation and max cost.

    Z3 reports each quantifier when its scope is popped and the rest at exit. Silicon asserts
    quantifiers with the same qid in many scopes, so instances are summed per qid.
    """
    profile = {}
    for path in paths:
        for line in open(path, errors='replace'):
            if not line.startswith('[quantifier_instances]'):
                continue
            fields = [f.strip() for f in line[len('[quantifier_instances]'):].split(' : ')]
            if len(fields) == 6:
                qid, instances, generation, cost = fields[0], fields[1], fields[4], fields[5]
            elif len(fields) == 4:
                qid, instances, generation, cost = fields
            else:
                continue
            entry = profile.setdefault(qid, [0, 0, 0.0])
            entry[0] += int(instances)
            entry[1] = max(entry[1], int(generation))
            entry[2] = max(entry[2], float(cost))
    return profile


def total_instances(run):
    return int(run['statistics'].get('quant-instantiations', sum(v[0] for v in run['profile'].values())))


def max_generation(run):
    return int(run['statistics'].get('max-generation', max((v[1] for v in run['profile'].values()), default=0)))


def loop_verdict(base, sweep, threshold, quants, cyclic=()):
    """Classifies a query from its base replay and its eager-threshold sweep.

    loop: max generation reaches the threshold at every threshold and total instances grow
    with it. Culprits are the quantifiers with the largest absolute growth in instances from
    the lowest to the highest threshold, among those that grow at least 1.5x and reach half
    the highest threshold in generation. Quantifiers on a trigger cycle (`cyclic`) come
    first: the others only consume the terms the cycle creates. The quantifier with the largest
    growth is always a culprit, even when it is not on a cycle.
    possible loop: max generation reaches the threshold only at the base threshold, or a hot
    quantifier feeds its own trigger.
    """
    runs = sorted(sweep + [(threshold, base)], key=lambda r: r[0])
    reaches = [max_generation(r) >= 0.8 * t for t, r in runs]
    totals = [total_instances(r) for _, r in runs]
    growing = len(runs) > 1 and all(reaches) and all(a < b for a, b in zip(totals, totals[1:]))
    culprits = []
    low, high = runs[0][1]['profile'], runs[-1][1]['profile']
    for qid, (instances, generation, _) in high.items():
        before = low.get(qid, [0, 0, 0])[0]
        if generation >= 0.5 * runs[-1][0] and instances >= 1.5 * max(before, 1):
            culprits.append((instances - before, qid))
    largest = max(culprits, default=(0, None))[1]
    culprits = [qid for _, _, qid in sorted(((qid in cyclic, growth, qid) for growth, qid in culprits),
                                             reverse=True)[:3]]
    if largest and largest not in culprits:
        culprits.append(largest)
    hot = top(base['profile'], 10)
    feeding = {qid: smtq.feeding_terms(quants[qid], quants[qid]) for qid, _ in hot if qid in quants}
    if growing and culprits:
        verdict = 'loop'
    elif max_generation(base) >= 0.8 * threshold or any(
            feeding.get(qid) and v[1] >= 0.5 * threshold for qid, v in hot):
        verdict = 'possible loop'
    else:
        verdict = 'no loop'
    return verdict, culprits, feeding


def top(profile, n):
    return sorted(profile.items(), key=lambda kv: -kv[1][0])[:n]


def history_caps(records, process):
    """Timeouts for earlier check-sats that took at least 5 s: twice their time, at most 10 s.

    Only slow queries get one, because setting a timeout changes Z3's search even when the
    query finishes well within it.
    """
    caps = {}
    for r in records:
        if r['process'] == process and r['seconds'] >= 5:
            cap = min(int(2000 * r['seconds']), 10000)
            caps[r['line']] = min(cap, r['timeout_ms']) if r['timeout_ms'] else cap
    return caps


def analyse_query(args, out, record, tap, sources, threshold, records=()):
    directory = out / f"q-{record['process']}-{record['index']}"
    directory.mkdir(exist_ok=True)
    tap_in = tap / f"{record['process']}.in"
    params = z3_params(json.loads((tap / f"{record['process']}.args").read_text()))
    params = override(params, [f'memory_max_size={args.memory_mb}', f'smt.qi.eager_threshold={threshold}'])
    timeout_ms = args.replay_timeout * 1000
    reproduced_below = min(0.3 * record['seconds'], 0.9 * args.replay_timeout)
    modes = [False, True] if args.history == 'auto' else [args.history == 'always']
    for history in modes:
        script = directory / ('prefix-history.smt2' if history else 'prefix.smt2')
        caps = history_caps(records, record['process']) if history else None
        cut(tap_in, record['line'], script, history, timeout_ms, caps=caps)
        baseline = None
        if history:
            baseline = directory / 'prefix-history-pre.smt2'
            cut(tap_in, record['line'], baseline, history, timeout_ms, final=False, caps=caps)
        base = replay(args, script, override(params, seed_args(args.seed)), script.stem + '-base', baseline)
        if base['wall_s'] >= reproduced_below or record['seconds'] < 1:
            break
    reproduced = base['wall_s'] >= reproduced_below
    quants = smtq.quantifiers(script)
    with ThreadPoolExecutor(len(args.cpus.split(','))) as pool:
        sweep = []
        if max_generation(base) >= 0.5 * threshold:
            sweep = list(zip(args.eager, pool.map(
                lambda t: replay(args, script, override(params, seed_args(args.seed)
                                                        + [f'smt.qi.eager_threshold={t}']),
                                 f'{script.stem}-eager{t}', baseline), args.eager)))
        seeds = list(zip(args.seeds, pool.map(
            lambda s: replay(args, script, override(params, seed_args(s)), f'{script.stem}-seed{s}', baseline),
            args.seeds)))
    hot = top(base['profile'], args.top_quantifiers)
    cycles = smtq.trigger_cycles([quants[q] for q, _ in hot if q in quants])
    verdict, culprits, feeding = loop_verdict(base, sweep, threshold, quants,
                                              {q for cycle in cycles for q in cycle})
    seed_runs = [(args.seed, base)] + [(s, r) for s, r in seeds if s != args.seed]
    walls = [max(r['wall_s'], 0.05) for _, r in seed_runs]
    seed_sensitive = len(seed_runs) > 1 and (
        max(walls) / min(walls) > 2 or len({r['result'] for _, r in seed_runs}) > 1)
    return dict(
        query=record, directory=str(directory), history=history, reproduced=reproduced,
        capped=sum(1 for line in (caps or {}) if line < record['line']),
        verdict=verdict, culprits=[dict(qid=q, source=smtq.describe(q, quants, sources)) for q in culprits],
        replay=summary(base),
        quantifiers=[dict(qid=q, instances=v[0], max_generation=v[1], max_cost=v[2],
                          source=smtq.describe(q, quants, sources), self_feeding=feeding.get(q, [])[:3])
                     for q, v in hot],
        trigger_cycles=cycles,
        eager_sweep=[dict(threshold=t, **summary(r),
                          top={q: r['profile'].get(q, [0])[0] for q, _ in hot[:5]})
                     for t, r in sorted(sweep + [(threshold, base)], key=lambda x: x[0])] if sweep else [],
        seeds=[dict(seed=s, **summary(r)) for s, r in seed_runs] if seeds else [],
        seed_sensitive=seed_sensitive if seeds else None)


def summary(run):
    s = run['statistics']
    return dict(wall_s=run['wall_s'], result=run['result'], reason_unknown=run['reason_unknown'],
                baseline_mismatch=run['baseline_mismatch'],
                instances=total_instances(run), max_generation=max_generation(run),
                max_memory_mb=s.get('max-memory'), conflicts=s.get('conflicts'))


def eager_threshold(tap_in):
    with open(tap_in, errors='replace') as f:
        for line in f:
            if line.startswith(EAGER):
                return int(line.split()[-1].rstrip(')'))
            if line.startswith('(check-sat'):
                break
    return 10


def write_report(out, meta, results):
    (out / 'report.json').write_text(json.dumps(dict(meta, queries=results), indent=1))
    lines = [f"# Matching-loop report: {meta['vpr']}", '',
             f"- Jar: {meta['jar']}", f"- Silicon: exit {meta['run']['code']}, {meta['run']['wall_s']} s wall",
             f"- Queries: {meta['queries']} in {meta['processes']} Z3 processes; "
             f"analysed the {len(results)} slowest (at least {meta['min_seconds']} s)",
             f"- Time in check-sats, summed over processes: {meta.get('check_sat_seconds')} s"]
    if (meta.get('idle_at_end') or 0) > 10:
        lines.append(f"- No Z3 output in the last {meta['idle_at_end']} s of the run: Silicon spent that time "
                     "outside the prover")
    lines += [
             f"- Replays: seed {meta['seed']}, eager threshold {meta['eager_threshold']}, "
             f"limit {meta['replay_timeout']} s", '']
    for r in results:
        q = r['query']
        lines += [f"## {q['process']} query {q['index']} (tap line {q['line']}): {r['verdict']}", '',
                  f"- Original: {q['seconds']:.2f} s, "
                  + ('no answer (still running at exit)' if q['result'] == 'none' else q['result'])
                  + (f", timeout {q['timeout_ms']} ms" if q['timeout_ms'] else ''),
                  f"- Replay ({'with' if r['history'] else 'without'} earlier queries): "
                  f"{fmt(r['replay'])}" + ('' if r['reproduced'] else ' — did not reproduce the original time')]
        if r['capped']:
            lines.append(f"- History replay capped {r['capped']} slow earlier queries at 10 s or twice their time")
        if any(s['baseline_mismatch'] for s in [r['replay']] + r['eager_sweep'] + r['seeds']):
            lines.append('- Warning: in some history replays the earlier queries ran differently from their '
                         'baseline (an earlier query hit its time cap), so per-quantifier counts are approximate')
        on_cycle = {q for cycle in r['trigger_cycles'] for q in cycle}
        for c in r['culprits']:
            role = ' (not on a trigger cycle)' if on_cycle and c['qid'] not in on_cycle else ''
            lines.append(f"- Culprit{role}: `{short(c['qid'])}` — {c['source']}")
        for cycle in r['trigger_cycles']:
            lines.append('- Trigger cycle among hot quantifiers: ' + ' → '.join(f'`{short(c)}`' for c in cycle))
        lines += ['', '| quantifier | instances | max gen | feeds own trigger | source |', '|---|---:|---:|---|---|']
        for item in r['quantifiers']:
            feeds = ', '.join(f'`{t[:60]}`' for t in item['self_feeding']) or ''
            lines.append(f"| `{short(item['qid'])[:70]}` | {item['instances']:,} | {item['max_generation']} | {feeds} | {item['source']} |")
        if r['eager_sweep']:
            names = list(r['eager_sweep'][0]['top'])
            lines += ['', '| eager threshold | time s | result | instances | max gen | '
                      + ' | '.join(f'`{short(n)[:30]}`' for n in names) + ' |',
                      '|---:|---:|---|---:|---:|' + '---:|' * len(names)]
            for s in r['eager_sweep']:
                lines.append(f"| {s['threshold']} | {s['wall_s']} | {s['result']} | {s['instances']:,} | "
                             f"{s['max_generation']} | " + ' | '.join(f"{s['top'][n]:,}" for n in names) + ' |')
        if r['seeds']:
            lines += ['', f"Seeds: {'seed-sensitive' if r['seed_sensitive'] else 'not seed-sensitive'} "
                      '(sensitive means max/min time > 2 or differing results)', '']
            for s in r['seeds']:
                lines.append(f"- seed {s['seed']}: {fmt(s)}")
        lines.append('')
    (out / 'report.md').write_text('\n'.join(lines))


def short(qid):
    return smtq.SOURCE.sub(lambda m: f"{m.group(1).rsplit('/', 1)[-1]}@{m.group(2)}@{m.group(3)}", qid)


def fmt(s):
    memory = f", {s['max_memory_mb']:.0f} MB" if s.get('max_memory_mb') else ''
    reason = f" ({s['reason_unknown']})" if s.get('reason_unknown') and s['result'] == 'unknown' else ''
    return (f"{s['wall_s']} s, {s['result']}{reason}, {s['instances']:,} instances, "
            f"max generation {s['max_generation']}{memory}")


def ints(text):
    return [int(x) for x in text.split(',') if x]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('vpr', type=Path)
    parser.add_argument('--jar', type=Path, help='Silicon jar; not needed with --reuse')
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--reuse', action='store_true', help='analyse the tap already in --out')
    parser.add_argument('--java', type=Path, default=Path(shutil.which('java') or 'java'))
    parser.add_argument('--z3', type=Path, default=Path(shutil.which('z3') or 'z3'))
    parser.add_argument('--cpus', default='4,12', help='taskset CPU list for Silicon and replays')
    parser.add_argument('--timeout', type=int, default=300, help='Silicon wall limit in seconds')
    parser.add_argument('--memory-mb', type=int, default=3000, help='Z3 memory_max_size')
    parser.add_argument('--seed', type=int, default=0, help='Z3 seed for the run and the base replay')
    parser.add_argument('--silicon-arg', action='append', default=[])
    parser.add_argument('--prover-arg', action='append', default=[], help='extra Z3 parameter for the run')
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--top', type=int, default=3, help='number of slowest queries to analyse')
    parser.add_argument('--min-seconds', type=float, default=1.0, help='ignore faster queries')
    parser.add_argument('--top-quantifiers', type=int, default=10)
    parser.add_argument('--replay-timeout', type=int, default=60, help='seconds per replay')
    parser.add_argument('--history', choices=['auto', 'never', 'always'], default='auto',
                        help='keep earlier check-sats in the prefix; auto retries with them '
                             'when the replay without them is much faster than the original')
    parser.add_argument('--eager', type=ints, default=[10, 30],
                        help='extra smt.qi.eager_threshold values for the loop sweep')
    parser.add_argument('--seeds', type=ints, default=[],
                        help='extra seeds for the seed-sensitivity sweep, e.g. 1,2')
    args = parser.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    tap = args.out / 'tap'
    if args.reuse:
        run = json.loads((args.out / 'run.json').read_text())
    else:
        if not args.jar:
            parser.error('--jar is required without --reuse')
        run = run_silicon(args, args.out)
    records = queries(tap, run.get('end'))
    with open(args.out / 'queries.tsv', 'w') as f:
        f.write('process\tindex\tline\tseconds\tresult\ttimeout_ms\trlimit\n')
        for r in records:
            f.write(f"{r['process']}\t{r['index']}\t{r['line']}\t{r['seconds']:.3f}\t{r['result']}\t"
                    f"{r['timeout_ms']}\t{r['rlimit']}\n")
    slow = sorted((r for r in records if r['seconds'] >= args.min_seconds), key=lambda r: -r['seconds'])
    sources = smtq.load_sources(args.vpr)
    results = []
    for record in slow[:args.top]:
        threshold = eager_threshold(tap / f"{record['process']}.in")
        results.append(analyse_query(args, args.out, record, tap, sources, threshold, records))
        print(f"{record['process']} q{record['index']}: {record['seconds']:.1f} s -> {results[-1]['verdict']} "
              + ' '.join(c['qid'] for c in results[-1]['culprits']), file=sys.stderr)
    last = last_output(tap)
    meta = dict(vpr=str(args.vpr), jar=run.get('jar'), run=run, queries=len(records),
                check_sat_seconds=round(sum(r['seconds'] for r in records), 2),
                idle_at_end=round(run['end'] - last, 2) if run.get('end') and last else None,
                processes=len(list(tap.glob('*.in'))), min_seconds=args.min_seconds, seed=args.seed,
                eager_threshold=threshold if results else None, replay_timeout=args.replay_timeout)
    write_report(args.out, meta, results)
    print(args.out / 'report.md')


if __name__ == '__main__':
    main()
