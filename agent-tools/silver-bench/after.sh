#!/bin/bash
# Profiles BASE and FULL, then runs the paired round (FULL against every other arm) on two CPUs
# ($BENCH_CPUS, default "6 7"). Needs $BENCH_DIR.
S=${BENCH_DIR:?set BENCH_DIR to the work directory}; TOOL=$(cd "$(dirname "$0")" && pwd)
read -r C1 C2 <<< "${BENCH_CPUS:-6 7}"
mkdir -p $S/prof $S/runs
JFR="-XX:FlightRecorderOptions=stackdepth=256"
$TOOL/run-arm.sh BASE $C1 $S/subset.txt $S/prof/BASE.jsonl 1 1 $S/warm300.txt $JFR -XX:StartFlightRecording=filename=$S/prof/BASE.jfr,settings=profile > $S/prof/BASE.log 2>&1 &
$TOOL/run-arm.sh FULL $C2 $S/subset.txt $S/prof/FULL.jsonl 1 1 $S/warm300.txt $JFR -XX:StartFlightRecording=filename=$S/prof/FULL.jfr,settings=profile > $S/prof/FULL.log 2>&1 &
wait; echo "$(date +%T) profiles done" >> $S/runs/pair.log
arms=$(ls $S/arms | grep -v -e '^FULL$' -e log | python3 -c "import sys,random; a=sys.stdin.read().split(); random.Random(7).shuffle(a); print(' '.join(a))")
lane() { for a in "$@"; do t0=$(date +%s); $TOOL/run-pair.sh $a $LANE $S/runs/pair-$a.jsonl 2> $S/runs/pair-$a.err; echo "$(date +%T) cpu$LANE $a rc=$? $(( $(date +%s)-t0 ))s load=$(cut -d' ' -f1 /proc/loadavg)" >> $S/runs/pair.log; done; }
set -- $arms; A=(); B=(); i=0; for a in "$@"; do if (( i % 2 == 0 )); then A+=($a); else B+=($a); fi; i=$((i+1)); done
LANE=$C1 lane "${A[@]}" & LANE=$C2 lane "${B[@]}" & wait
echo "$(date +%T) paired round done" >> $S/runs/pair.log
