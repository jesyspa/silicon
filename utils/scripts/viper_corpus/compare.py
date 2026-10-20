#!/usr/bin/env python3
"""Compare matched Viper results; ratios are candidate / baseline."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path

from run import read_jsonl


def load(directory):
    rows = read_jsonl(directory / 'results.jsonl')
    indexed = {row['path']: row for row in rows}
    if len(indexed) != len(rows):
        raise ValueError('duplicate result paths: ' + str(directory))
    return indexed


def ratio(old, new, metric, verified_only=False):
    a, b = old.get(metric), new.get(metric)
    eligible = ('verified',) if verified_only else ('verified', 'failed')
    if (old['outcome'] != new['outcome'] or old['outcome'] not in eligible
            or not isinstance(a, (int, float)) or not isinstance(b, (int, float))
            or not math.isfinite(a) or not math.isfinite(b) or a <= 0 or b <= 0):
        return None
    return b / a


def compare(baseline, candidate, top=10, verified_only=False):
    matched = sorted(set(baseline) & set(candidate))
    changed_inputs = [p for p in matched if baseline[p]['sha256'] != candidate[p]['sha256']
                      or baseline[p]['group'] != candidate[p]['group']]
    matched = [p for p in matched if p not in set(changed_inputs)]
    groups = {}
    for group in sorted({baseline[p]['group'] for p in matched}):
        paths = [p for p in matched if baseline[p]['group'] == group]
        transitions = Counter(baseline[p]['outcome'] + ' -> ' + candidate[p]['outcome']
                              for p in paths if baseline[p]['outcome'] != candidate[p]['outcome'])
        entry = dict(matched=len(paths), outcome_changes=dict(transitions), metrics={})
        for metric in ('solver_cpu_s', 'wall_s'):
            pairs = [dict(path=p, ratio=r, baseline=baseline[p][metric], candidate=candidate[p][metric])
                     for p in paths if (r := ratio(baseline[p], candidate[p], metric, verified_only)) is not None]
            ratios = [p['ratio'] for p in pairs]
            entry['metrics'][metric] = dict(
                pairs=len(pairs), excluded=len(paths) - len(pairs),
                geometric_mean_ratio=math.exp(sum(math.log(r) for r in ratios) / len(ratios))
                if ratios else None,
                regressions=sorted((p for p in pairs if p['ratio'] > 1),
                                   key=lambda p: (-p['ratio'], p['path']))[:top],
                improvements=sorted((p for p in pairs if p['ratio'] < 1),
                                    key=lambda p: (p['ratio'], p['path']))[:top])
        groups[group] = entry
    return dict(ratio_direction='candidate / baseline', groups=groups,
                baseline_only=sorted(set(baseline) - set(candidate)),
                candidate_only=sorted(set(candidate) - set(baseline)),
                changed_inputs=changed_inputs,
                outcome_changes=[dict(path=p, baseline=baseline[p]['outcome'],
                                      candidate=candidate[p]['outcome'])
                                 for p in matched if baseline[p]['outcome'] != candidate[p]['outcome']])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--top', type=int, default=10)
    parser.add_argument('--verified-only', action='store_true',
                        help='exclude failed files from timing ratios; retain outcome transitions')
    args = parser.parse_args(argv)
    if args.top < 0:
        parser.error('top must be nonnegative')
    try:
        print(json.dumps(compare(load(args.baseline), load(args.candidate), args.top, args.verified_only), indent=2))
    except (OSError, ValueError) as exc:
        parser.exit(1, str(exc) + '\n')


if __name__ == '__main__':
    main()
