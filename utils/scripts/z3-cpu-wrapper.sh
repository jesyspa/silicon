#!/bin/sh
set -eu
: "${SOLVER_CGROUP:?set SOLVER_CGROUP to the dedicated cgroup-v2 leaf}"
: "${SOLVER_Z3:?set SOLVER_Z3 to the Z3 executable}"
printf '%s\n' "$$" > "$SOLVER_CGROUP/cgroup.procs"
exec "$SOLVER_Z3" "$@"
