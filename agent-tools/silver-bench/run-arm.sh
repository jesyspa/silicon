#!/bin/bash
# run-arm.sh ARM CPU LIST OUT WARM PASSES [WARMLIST [extra JVM args]]
# Runs SilverBench on one arm, pinned to CPU. Needs $BENCH_DIR; corpus is $BENCH_CORPUS (default ~/data/viper-corpus).
S=${BENCH_DIR:?set BENCH_DIR to the work directory}; TOOL=$(cd "$(dirname "$0")" && pwd)
CORPUS=${BENCH_CORPUS:-$HOME/data/viper-corpus}
arm=$1; cpu=$2; list=$3; out=$4; warm=$5; passes=$6; warmlist=${7:-$3}; shift 6; [ $# -gt 0 ] && shift
exec systemd-run --user --scope --quiet -p MemoryMax=6G -p MemorySwapMax=0 taskset -c $cpu \
  java -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xss64m -Xms2g -Xmx3g "$@" \
  -cp "$TOOL/harness/target/scala-2.13/classes:$S/arms/$arm/classes:$(cat $S/deps.txt)" \
  SilverBench "$list" "$CORPUS" "$out" "$warm" "$passes" "$arm" "$warmlist"
