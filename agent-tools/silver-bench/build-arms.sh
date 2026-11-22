#!/bin/bash
# build-arms.sh SILVER_REPO [FULL_REV [BASE_REV]]
# Prepares $BENCH_DIR for a leave-one-out benchmark of a Silver commit series and builds every arm into
# $BENCH_DIR/arms/NAME/classes:
#   FULL  the whole series (FULL_REV, default upstream/frontend-4-whole-program-passes)
#   BASE  upstream (BASE_REV, default origin/master)
#   cNN-* FULL with one change left out
# The arm definitions below are specific to the frontend-speedups series (commits ff74ac59, 737d5f2d,
# a0566ea4, 88fb7a56); adapt them for another series. Also writes cp-full.txt, deps.txt, deps-shared.txt and
# reflect-jar.txt, which the harness build and the run scripts read. Builds run on $BUILD_CPUS (default 0-5,8-13).
set -u
S=${BENCH_DIR:?set BENCH_DIR to the work directory}
REPO=${1:?usage: build-arms.sh SILVER_REPO [FULL_REV [BASE_REV]]}; FULL_REV=${2:-upstream/frontend-4-whole-program-passes}; BASE_REV=${3:-origin/master}
BUILD_CPUS=${BUILD_CPUS:-0-5,8-13}
W=$S/silver-arms
mkdir -p $S/arms $S/patches
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

# Sub-change patches for the first three commits, which bundle several independent changes
git show ff74ac59 -- src/main/scala/viper/silver/parser/ParseAst.scala > $S/patches/c01-subnodecache.patch
git show ff74ac59 -- src/main/scala/viper/silver/parser/MacroExpander.scala > $S/patches/c01-namesinscope.patch
git show ff74ac59 -- src/main/scala/viper/silver/ast/utility/rewriter/ > $S/patches/c01-childfilter.patch
git show a0566ea4 -- src/main/scala/viper/silver/plugin/standard/adt/ > $S/patches/c03-adt.patch
git show a0566ea4 -- src/main/scala/viper/silver/plugin/standard/termination/ > $S/patches/c03-termination.patch

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
prep $FULL; git apply -R $S/patches/c01-childfilter.patch && build c01-childfilter
prep $FULL; sed -i '/^      _subnodesCache = c$/d' src/main/scala/viper/silver/parser/ParseAst.scala && build c01-subnodecache
prep $FULL; sed -i 's/namesInScopeCache.getOrElseUpdate(macroCall, NameAnalyser().namesInScope(program, Some(macroCall)))/NameAnalyser().namesInScope(program, Some(macroCall))/' src/main/scala/viper/silver/parser/MacroExpander.scala && build c01-namesinscope
prep $FULL; git revert --no-commit 737d5f2d >/dev/null && build c02-macro-all
prep $FULL; sed -i 's/macrosByName.get(name.name)/macros.find(_.idndef.name == name.name)/' src/main/scala/viper/silver/parser/MacroExpander.scala && build c02-macro-index
prep $FULL; git apply -R $S/patches/c03-adt.patch && build c03-adt
prep $FULL; git apply -R $S/patches/c03-termination.patch && build c03-termination
# Every later commit: FULL with that commit reverted
n=4
for c in $(git rev-list --reverse $BASE_REV..$FULL | tail -n +4); do
  prep $FULL; git revert --no-commit $c >/dev/null && build "$(printf 'c%02d' $n)-$(git log -1 --format=%s $c | tr 'A-Z ' 'a-z-' | tr -cd 'a-z0-9-' | cut -c1-40)"
  git revert --abort 2>/dev/null; n=$((n+1))
done
# 88fb7a56 does not revert cleanly as a whole; leave out only its ImpureAssumeRewriter change
prep $FULL; git show 88fb7a56 -- src/main/scala/viper/silver/ast/utility/ImpureAssumeRewriter.scala | git apply -R && build c19-skip-the-rewriting-of-assume-statements-
prep $FULL
echo done
