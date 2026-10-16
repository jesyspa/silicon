#!/usr/bin/env bash
set -euo pipefail

# The caller supplies the target worktree as the working directory.
git submodule update --init --recursive -- silver
