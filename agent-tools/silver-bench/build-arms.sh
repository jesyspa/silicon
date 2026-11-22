#!/bin/bash
# build-arms.sh SILVER_REPO FULL_REV [BASE_REV]
# Prepares $BENCH_DIR for a leave-one-out benchmark of a Silver commit series and builds every arm into
# $BENCH_DIR/arms/NAME/classes:
#   FULL  the whole series (FULL_REV)
#   BASE  upstream (BASE_REV, default origin/master)
#   cNN-* FULL with one change left out
# Each commit should be one change; a commit that does not revert cleanly on FULL is reported and skipped.
# Also writes cp-full.txt, deps.txt, deps-shared.txt and
# reflect-jar.txt, which the harness build and the run scripts read. Builds run on $BUILD_CPUS (default 0-5,8-13).
set -u
S=${BENCH_DIR:?set BENCH_DIR to the work directory}
REPO=${1:?usage: build-arms.sh SILVER_REPO FULL_REV [BASE_REV]}; FULL_REV=${2:?usage: build-arms.sh SILVER_REPO FULL_REV [BASE_REV]}; BASE_REV=${3:-origin/master}
BUILD_CPUS=${BUILD_CPUS:-0-5,8-13}
W=$S/silver-arms
mkdir -p $S/arms
[ -d $W ] || git -C "$REPO" worktree add -q --detach $W "$FULL_REV" || exit 1
cd $W
FULL=$(git rev-parse "$FULL_REV"); echo $FULL > $S/full.sha
prep() { git reset -q --hard "$1"; git clean -qfd src; echo 'sbt.version=1.10.11' > project/build.properties; }

# Classpath of the FULL build; its first entry is Silver's own class directory, the rest are the dependencies.
# The paired launcher gives each arm its own scala-reflect, so it is kept out of the shared classpath.
prep $FULL
if [ ! -s $S/cp-full.txt ]; then
  sbt -batch 'export Runtime/fullClasspath' 2>&1 | tail -1 > $S/cp-full.txt
  cut -d: -f2- $S/cp-full.txt > $S/deps.txt
  tr ':' '\n' < $S/deps.txt | grep -v 'scala-reflect' | paste -sd: > $S/deps-shared.txt
  tr ':' '\n' < $S/deps.txt | grep 'scala-reflect' > $S/reflect-jar.txt
fi

build() {
  local name=$1
  if [ -d $S/arms/$name/classes ]; then echo "skip $name"; return; fi
  if taskset -c $BUILD_CPUS sbt -batch compile > $S/arms/build-$name.log 2>&1; then
    mkdir -p $S/arms/$name && cp -a target/scala-2.13/classes $S/arms/$name/classes
    git diff --stat "$FULL" > $S/arms/$name/diffstat.txt; echo "built $name"
  else echo "FAILED $name"; fi
}
prep $FULL; build FULL
prep $BASE_REV; build BASE
# Every commit of the series: FULL with that commit reverted
n=1
for c in $(git rev-list --reverse $BASE_REV..$FULL); do
  name="$(printf 'c%02d' $n)-$(git log -1 --format=%s $c | tr 'A-Z ' 'a-z-' | tr -cd 'a-z0-9-' | cut -c1-40)"
  prep $FULL
  if git revert --no-commit $c >/dev/null 2>&1; then build "$name"; else echo "REVERT FAILED $name ($c)"; fi
  git revert --abort 2>/dev/null; n=$((n+1))
done
prep $FULL
echo done
