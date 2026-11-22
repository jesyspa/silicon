#!/bin/bash
# run-pair.sh ARM_B CPU OUT : FULL against ARM_B in one JVM (see launcher/PairedLauncher.java).
# Needs $BENCH_DIR; corpus is $BENCH_CORPUS (default ~/data/viper-corpus).
S=${BENCH_DIR:?set BENCH_DIR to the work directory}; TOOL=$(cd "$(dirname "$0")" && pwd)
CORPUS=${BENCH_CORPUS:-$HOME/data/viper-corpus}
if [ ! -f $S/launcher/PairedLauncher.class ] || [ $TOOL/launcher/PairedLauncher.java -nt $S/launcher/PairedLauncher.class ]; then
  mkdir -p $S/launcher && javac -d $S/launcher $TOOL/launcher/PairedLauncher.java || exit 1
fi
exec systemd-run --user --scope --quiet -p MemoryMax=7G -p MemorySwapMax=0 taskset -c $2 \
  java -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss64m -Xms3g -Xmx4g -XX:ReservedCodeCacheSize=512m \
  -Dbench.dir=$S -Dbench.harness=$TOOL/harness/target/scala-2.13/classes \
  -cp "$S/launcher:$(cat $S/deps-shared.txt)" PairedLauncher FULL "$1" $S/subset.txt $S/warm300.txt "$CORPUS" "$3"
