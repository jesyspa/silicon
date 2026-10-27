#!/bin/bash
# Nightly benchmark: fork origin/main (F) against upstream master head (U), plus the previous night's fork JAR (P)
# when its commit differs. Same corpus, manifest, profile, caps, seeds, priming and lanes as the impact runs of
# 2026-10-27: every arm capped at 8x the file's profiled median (min 60 s), at least 4 seeds per file, runners primed
# on files with a profiled median below 10 s, 5 lanes of 7G in silicon.slice. Ratios are against U.
#
# Layout under $NIGHTLY_ROOT (default ~/data/viper-corpus-results/nightly):
#   inputs/{manifest.jsonl,profile.json,z3}  corpus manifest, noise.py profile and Z3 binary, installed once by hand
#   src/fork, src/upstream                   dedicated clones; only fetched, never pushed (push URLs are disabled)
#   jars/<commit>/{silicon.jar,COMMIT}       one JAR per Silicon commit, reused while the commit is unchanged
#   <date>/{arms.env,harness,run,analysis.json,analysis.txt}
# Rerunning on the same date resumes <date>/run with the arms in <date>/arms.env.
# Holds ~/data/viper-corpus-results/.suite.lock for the whole run; no task starts outside $NIGHTLY_WINDOW.
# Overrides for test runs: NIGHTLY_DATE (an output name that is not a date is never taken as a previous night),
# NIGHTLY_MANIFEST, NIGHTLY_MIN_SEEDS, NIGHTLY_SEED_POLICY, NIGHTLY_WINDOW, NIGHTLY_LANES, NIGHTLY_BUILD_CPUS.
set -euo pipefail
N=${NIGHTLY_ROOT:-$HOME/data/viper-corpus-results/nightly}
DATE=${NIGHTLY_DATE:-$(date +%F)}
WINDOW=${NIGHTLY_WINDOW:-23:00-07:00}
LANES=${NIGHTLY_LANES:-2,10;3,11;6,14;7,15;5,13}
BUILD_CPUS=${NIGHTLY_BUILD_CPUS:-4,12}
MANIFEST=${NIGHTLY_MANIFEST:-$N/inputs/manifest.jsonl}
MIN_SEEDS=${NIGHTLY_MIN_SEEDS:-4}
SEED_POLICY=${NIGHTLY_SEED_POLICY:-}
FLAGS="-J-Dsilicon.exp.seqDistinctInverse=true -J-Dsilicon.exp.exactReborrow=true"
LOCK=$HOME/data/viper-corpus-results/.suite.lock
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.10-tem
SBT=$HOME/.sdkman/candidates/sbt/current/bin/sbt
OUT=$N/$DATE

say() { echo "$(date -Is) $*"; }

# Seconds until the end of the window, so the lock wait and the builds give up when no task could start.
until_end() {
  [ "$WINDOW" = any ] && { echo 86400; return; }
  local end now
  end=$(date -d "${WINDOW#*-}" +%s); now=$(date +%s)
  [ "$end" -le "$now" ] && end=$(date -d "tomorrow ${WINDOW#*-}" +%s)
  echo $((end - now))
}

clone() {  # clone DIR REMOTE URL
  [ -d "$1/.git" ] && return
  git clone -q --origin "$2" --no-checkout "$3" "$1"
  git -C "$1" remote set-url --push "$2" DISABLED
}

# build NAME DIR COMMIT [SBT_ARG...]: print the JAR of COMMIT, building it when missing.
build() {
  local name=$1 dir=$2 commit=$3; shift 3
  local jar=$N/jars/$commit/silicon.jar
  if [ ! -f "$jar" ]; then
    say "build $name $commit" >&2
    git -C "$dir" checkout -q --force --detach "$commit"
    if [ "$name" = F ]; then
      git -C "$dir" -c submodule.silver.url=https://github.com/jesyspa/silver.git submodule update -q --init --force silver
    else
      git -C "$dir" submodule update -q --init --force silver
    fi
    (cd "$dir" && timeout "$(until_end)" taskset -c "$BUILD_CPUS" "$SBT" -batch "$@" clean silver/clean assembly) \
      < /dev/null > "$OUT/build-$name.log" 2>&1
    mkdir -p "$N/jars/$commit"
    cp "$dir/target/scala-2.13/silicon.jar" "$jar.tmp"
    echo "$commit silver $(git -C "$dir/silver" rev-parse HEAD) built $(date -Is)" > "$N/jars/$commit/COMMIT"
    mv "$jar.tmp" "$jar"
  fi
  echo "$jar"
}

mkdir -p "$OUT" "$N/src" "$N/jars"
exec 9> "$LOCK"
say "waiting for $LOCK"
flock -w "$(until_end)" 9 || { say "lock not free before the window ends"; exit 1; }
say "start $DATE"

if [ ! -f "$OUT/arms.env" ]; then
  clone "$N/src/fork" origin https://github.com/jesyspa/silicon.git
  clone "$N/src/upstream" upstream https://github.com/viperproject/silicon.git
  git -C "$N/src/fork" fetch -q origin main
  git -C "$N/src/upstream" fetch -q upstream master
  F=$(git -C "$N/src/fork" rev-parse FETCH_HEAD)
  U=$(git -C "$N/src/upstream" rev-parse FETCH_HEAD)
  FJ=$(build F "$N/src/fork" "$F")
  UJ=$(build U "$N/src/upstream" "$U" -Dsbt.version=1.10.11)
  rm -rf "$OUT/harness"
  cp -r "$N/src/fork/utils/scripts" "$OUT/harness"
  ARMS=("F=$FJ $FLAGS" "U=$UJ")
  P=
  for prev in $(ls -d "$N"/20??-??-?? 2>/dev/null | sort -r); do
    [ "$(basename "$prev")" \< "$DATE" ] && [ -f "$prev/arms.env" ] || continue
    P=$(. "$prev/arms.env"; echo "$F")
    break
  done
  if [ -n "$P" ] && [ "$P" != "$F" ] && [ -f "$N/jars/$P/silicon.jar" ]; then
    ARMS+=("P=$N/jars/$P/silicon.jar $FLAGS")
  else
    P=
  fi
  { echo "F=$F"; echo "U=$U"; echo "P=$P"; declare -p ARMS; } > "$OUT/arms.env.tmp"
  mv "$OUT/arms.env.tmp" "$OUT/arms.env"
fi
. "$OUT/arms.env"
say "arms F=$F U=$U P=${P:-none}"

ARM_OPTS=()
for a in "${ARMS[@]}"; do ARM_OPTS+=(--arm "$a"); done
python3 "$OUT/harness/viper_corpus/night.py" --corpus-root ~/data/viper-corpus --manifest "$MANIFEST" --output-directory "$OUT/run" \
  --java "$JAVA_HOME/bin/java" --z3 "$N/inputs/z3" \
  --lanes "$LANES" --lane-memory-max 7G --jvm-arg=-Xmx4g \
  --verifier-arg=--proverArgs --verifier-arg=memory_max_size=3000 \
  --warmup DaG/gobra/dafny_book/chap08.vpr --order-seed "$(echo "$DATE" | tr -cd 0-9)" --window "$WINDOW" \
  --baseline U --cap-factor 8 --cap-min 60 --profile "$N/inputs/profile.json" --min-seeds "$MIN_SEEDS" \
  --seed-policy "$SEED_POLICY" --prime-below 10 "${ARM_OPTS[@]}"
python3 "$OUT/harness/viper_corpus/analyze.py" --baseline U --json "$OUT/analysis.json" "$OUT/run" > "$OUT/analysis.txt"
say "done $DATE"
