#!/usr/bin/env python3
"""Profile summary for a SilverBench JFR recording: share of samples per phase (inclusive), and per phase the
methods with the most samples as innermost Silver/fastparse frame. usage: profsum.py REC.jfr [N]"""
import pickle, subprocess, sys, os, collections, re
jfr = sys.argv[1]; N = int(sys.argv[2]) if len(sys.argv) > 2 else 12
if not os.path.exists(jfr + '.pkl'):
    # jfrstacks.py parses the recording with `jfr print` and caches the stacks in REC.jfr.pkl
    jfrstacks = os.environ.get('JFRSTACKS', os.path.expanduser('~/data/viper-corpus-results/lazy-optional-20261120/bin/jfrstacks.py'))
    subprocess.run([sys.executable, jfrstacks, jfr, 'zzzz', '1'], stdout=subprocess.DEVNULL, check=True)
samples = [st for st in pickle.load(open(jfr + '.pkl', 'rb')) if any('SilverBench' in f for f, _ in st)]
PH = [('Parsing', 'Frontend.parsing'), ('Semantic Analysis', 'semanticAnalysis'), ('Translation', '.translation'),
      ('Consistency Check', 'consistencyCheck'), ('Verification (plugins)', '.verification'), ('Backend utilities', 'backendUtilities'),
      ('Setup', 'BenchFrontend.setup')]
def phase(st):
    for name, pat in PH:
        if any(pat in f for f, _ in st): return name
    return 'other'
def owner(st):
    for f, _ in st:
        if f.startswith(('viper.', 'fastparse.')) and 'SilverBench' not in f: return f
    return st[0][0] if st else '?'
n = len(samples); print(f'{n} samples in Silver work')
byph = collections.defaultdict(list)
for st in samples: byph[phase(st)].append(st)
for name, _ in PH + [('other', '')]:
    ss = byph.get(name, [])
    if not ss: continue
    print(f'\n{name}: {100*len(ss)/n:.1f}%')
    for m, c in collections.Counter(owner(st) for st in ss).most_common(N):
        print(f'   {100*c/n:5.1f}%  {m}')
# inclusive shares of notable subsystems across all phases
print('\nInclusive, all phases:')
for pat in ['fastparse', 'MacroExpander', 'NameAnalyser', 'TypeChecker', 'PTypeSubstitution', 'initProperties', 'rewriter.Strategy',
            'Rewritable', 'Visitor', 'Consistency', 'Program.check', 'DomainInstances', 'QuantifiedPermissions', 'termination', 'adt.',
            'hashCode', 'scala.reflect', 'Translator', 'ImpureAssumeRewriter', 'PositionParsing', 'subnodes']:
    c = sum(1 for st in samples if any(pat in f for f, _ in st))
    print(f'   {100*c/n:5.1f}%  {pat}')
