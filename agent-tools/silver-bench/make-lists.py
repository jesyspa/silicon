#!/usr/bin/env python3
"""Writes the file lists into $BENCH_DIR: all-files.txt (every .vpr under the corpus), subset.txt (every file
outside Ru2/ plus 400 Ru2/ files picked with seed 1; 1259 files on the 4858-file corpus) and warm300.txt (300
files of subset.txt picked with seed 2, used for JIT warm-up). Corpus: $BENCH_CORPUS, default ~/data/viper-corpus."""
import os, random, subprocess
S = os.environ['BENCH_DIR']
corpus = os.environ.get('BENCH_CORPUS', os.path.expanduser('~/data/viper-corpus'))
# The Ru2 sample depends on this order: locale-aware `sort`, as in the original run (en_GB.UTF-8)
fs = subprocess.run("find . -name '*.vpr' | sed 's|^\\./||' | sort", shell=True, cwd=corpus, capture_output=True,
                    text=True, check=True).stdout.splitlines()
open(S + '/all-files.txt', 'w').write('\n'.join(fs) + '\n')
ru2 = [f for f in fs if f.startswith('Ru2/')]; rest = [f for f in fs if not f.startswith('Ru2/')]
random.Random(1).shuffle(ru2)
sel = sorted(rest + ru2[:400])
open(S + '/subset.txt', 'w').write('\n'.join(sel) + '\n')
warm = sel[:]; random.Random(2).shuffle(warm)
open(S + '/warm300.txt', 'w').write('\n'.join(warm[:300]) + '\n')
print(len(fs), 'files,', len(sel), 'in subset.txt, 300 in warm300.txt')
