# Oliphaunt newcomer setup log

29 October 2026, 14:35–15:05 CET. Operator account: `kameliya`, reached with `ssh oliphaunt`. Silicon builds and verifies its README example; SnaKt builds and passes all 171 tests. jormungandr builds, but its default test run has 595 passes and one golden-comparison failure. Private access initially failed; operator-approved key registration and an outbound firewall change resolved it. The corpus/harness transfer and U/S smoke task work. After the initial step-3 access failure, the dispatcher authorized step 4 before resuming jormungandr.

This setup session used no sudo, key/token transfer, system-tool replacement, upstream repository operation, or contact with Viper/JetBrains maintainers. The dispatcher separately arranged the operator-approved authentication/firewall changes described in step 3. Public clones use the `jesyspa` forks. Commands below ran on oliphaunt unless stated otherwise. Durations are wall seconds from `/usr/bin/time -p` unless marked approximate/unmeasured; they include cold dependency downloads, not just compilation. This is a setup exercise, not a performance comparison.

## Initial environment and network

Observed 20 logical CPUs, 31 GiB RAM (22 GiB free, 26 GiB available), 64 GiB unused swap. Installed tools included Git, curl, wget, tar, unzip, Python, gh 2.46, Java 25.0.4.1 and Z3 4.8.7. There was initially no `~/dev`, sbt, Gradle, or sdkman.

Created a remote scratch directory with `mktemp -d /tmp/bot-run.XXXXXXXXXX`: `/tmp/bot-run.eFGT3r3Vbg` (called `$R` below). Raw download/build/test/access logs remain there; it is temporary storage, not a permanent evidence archive.

```sh
whoami; nproc; free -h
command -v curl wget git unzip tar python3
timeout 15 curl -IL --connect-timeout 5 https://github.com
timeout 15 ssh -o BatchMode=yes -o ConnectTimeout=5 -T git@github.com
timeout 15 ssh -o BatchMode=yes -o ConnectTimeout=5 -p 443 -T git@ssh.github.com
gh auth status
```

- HTTPS: HTTP 200 in approximately 4 s.
- SSH port 22: `Connection timed out` after approximately 5 s. Bounding the check avoids the previously observed hang.
- SSH port 443: `Host key verification failed.` The endpoint was reachable but not yet trusted by this account; this did not establish whether authentication worked.
- gh: `You are not logged into any GitHub hosts.` No login attempted.
- Whole initial inspection: approximately 10 s, not individually timed beyond the network limits.

Public HTTPS clones can proceed without GitHub credentials. Private access was tested again at step 3, with an isolated known-hosts file.

## 1. Silicon: works

Followed [silicon-team-summary.md, “Build and try”](silicon-team-summary.md#build-and-try), plus the fork README's sbt link and sbt's public [Linux installation instructions](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Linux.html).

### Checkout

```sh
export GIT_TERMINAL_PROMPT=0
mkdir -p ~/dev
git clone --branch main https://github.com/jesyspa/silicon.git ~/dev/silicon
cd ~/dev/silicon
git submodule update --init --recursive
git rev-parse HEAD
```

Clone: 9.03 s; Silver initialization: 9.22 s. This splits the documented recursive clone into two commands so the submodule URL could be checked first; `.gitmodules` points to `https://github.com/jesyspa/silver.git`.

- Silicon main: `45397fd755b47600dceb445d6492123dcf8b7584`.
- Pinned Silver: `bda9a9da199d688a0543a0f0ab9453701dfa3cc1`.

SnaKt's public clone was prefetched after the Silicon clone (1.31 s); its documentation/build/test work followed the completed Silicon trial.

### User-local toolchain

The “Build and try” section requires JDK 21/Z3 4.16.0 but does not explain installing those or sbt without root. The main README instead says Java 11 and links an upstream clone/wiki. Used the task's specified JDK 21 and fork URL; did not follow the upstream links.

Queried the public release APIs to select Linux x86-64 archives:

```sh
curl -fsSL --connect-timeout 10 \
  'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux' \
  > "$R/jdk-assets.json"
curl -fsSL --connect-timeout 10 \
  https://api.github.com/repos/Z3Prover/z3/releases/tags/z3-4.16.0 \
  > "$R/z3-assets.json"
```

An initial unquoted Adoptium URL failed in the account's default zsh with `no matches found: https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux`. Retried quoted, running subsequent command blocks through `ssh oliphaunt bash -s`. No installation happened during that failed API request. API queries/reading docs were not separately timed.

Installed these archives (the JDK API selected Temurin 21.0.13+9):

```sh
mkdir -p ~/.local/opt ~/.local/bin
curl -fL --connect-timeout 10 \
  'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz' \
  -o "$R/jdk.tar.gz"
tar -xzf "$R/jdk.tar.gz" -C ~/.local/opt
curl -fL --connect-timeout 10 \
  https://github.com/Z3Prover/z3/releases/download/z3-4.16.0/z3-4.16.0-x64-glibc-2.39.zip \
  -o "$R/z3.zip"
unzip -q "$R/z3.zip" -d ~/.local/opt
curl -fL https://github.com/sbt/sbt/releases/download/v1.10.11/sbt-1.10.11.tgz \
  -o "$R/sbt.tgz"
tar -xzf "$R/sbt.tgz" -C ~/.local/opt
```

| Operation | Wall seconds |
|---|---:|
| JDK download / extraction | 69.85 / 1.10 |
| Z3 download / extraction | 8.88 / 0.81 |
| sbt download | 7.71 |
| sbt extraction | Not separately timed |

The sbt install page offered 1.10.10; selected 1.10.11 to match Silicon's `project/build.properties`. The Temurin archive's SHA-256 matched its API metadata: `d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c`. Z3's Linux binary ran without a library compatibility error.

Created `~/.local/opt/benchmark-env.sh` as a convenience, an extra step absent from the project instructions:

```sh
export JAVA_HOME="$HOME/.local/opt/jdk-21.0.13+9"
export PATH="$JAVA_HOME/bin:$HOME/.local/opt/z3-4.16.0-x64-glibc-2.39/bin:$HOME/.local/opt/sbt/bin:$PATH"
export Z3_EXE="$HOME/.local/opt/z3-4.16.0-x64-glibc-2.39/bin/z3"
```

Source it explicitly in each benchmark shell. No shell startup file was edited. Version checks reported Temurin 21.0.13 and Z3 4.16.0.

### Build and example

```sh
source ~/.local/opt/benchmark-env.sh
cd ~/dev/silicon
/usr/bin/time -p sbt assembly > "$R/silicon-assembly.log" 2>&1
```

Succeeded, 73.16 s wall (sbt reports 52 s for the task). It fetched sbt/Scala/dependencies automatically. Compiler warnings included unused variables, deprecated APIs, non-exhaustive matches, and exhaustivity-depth limits; none blocked assembly. No source/build-file modifications were needed.

The summary's `example.vpr` is a placeholder, not a supplied file. Used the complete Viper example embedded in the public README rather than inventing a test:

```sh
python3 - <<'PY'
from pathlib import Path
s = Path('README.md').read_text()
Path('/tmp/bot-run.eFGT3r3Vbg/example.vpr').write_text(
    s.split('```text\n')[1].split('```')[0])
PY
/usr/bin/time -p java -jar target/scala-2.13/silicon.jar "$R/example.vpr" \
  > "$R/silicon-example.log" 2>&1
```

Succeeded, 1.55 s wall: `Silicon finished verification successfully in 1.34s.` Also printed `Decider 02 skipped 6 contradicted checks`. No error.

## 2. SnaKt: works, 171 tests pass

Followed the public fork README and its linked `docs/developing.md`. Checkout:

```sh
git clone https://github.com/jesyspa/SnaKt.git ~/dev/SnaKt
cd ~/dev/SnaKt
git rev-parse HEAD
```

Clone: 1.31 s. Commit: `e8d86408298192f121ba835a4f5d87ecf388356d`.

The README prescribes `publishToMavenLocal`; the developing guide supplies `./gradlew test`. No global Gradle install is needed: the checked-in executable wrapper downloaded Gradle 8.14.3. Continued with user-local JDK 21. SnaKt's README explicitly requires Z3 4.8.7, unlike the Silicon trial, so used oliphaunt's existing `/usr/bin/z3`:

```sh
source ~/.local/opt/benchmark-env.sh
cd ~/dev/SnaKt
export Z3_EXE=/usr/bin/z3
"$Z3_EXE" --version
/usr/bin/time -p ./gradlew publishToMavenLocal > "$R/snakt-publish.log" 2>&1
/usr/bin/time -p ./gradlew test > "$R/snakt-test.log" 2>&1
```

| Command | Wall seconds | Result |
|---|---:|---|
| `./gradlew publishToMavenLocal` | 146.30 | BUILD SUCCESSFUL, 50 tasks executed |
| `./gradlew test` | 409.80 | BUILD SUCCESSFUL, 12 executed / 31 up-to-date |

JUnit XML under `formver.compiler-plugin/build/test-results/test/` reports 142 tests; `formver.compiler-plugin/locality/build/test-results/test/` reports 29. Total: 171 tests, zero failures/errors/skips. These are default README-toolchain tests, not a test of SnaKt with Z3 4.16.0 or of the newly built Silicon artifact. No local Silicon publishing or dependency overrides were needed.

Friction: the README's Z3 example includes `sudo cp`, but the required version already exists and can be selected with `Z3_EXE`. The README correctly warns that daemons capture that variable; set it before starting Gradle. No daemon restart was necessary because no SnaKt daemon had been started with the other solver. Neither build nor test required patches or produced error/deprecation messages in the captured Gradle logs.

## 3. jormungandr: builds; one test fails

### Initial access failure

The first checks retrieved no private repository contents. Tested the requested URL with bounded, noninteractive authentication:

```sh
cd ~/dev
/usr/bin/time -p timeout 15 env GIT_TERMINAL_PROMPT=0 \
  GIT_SSH_COMMAND='ssh -o BatchMode=yes -o ConnectTimeout=5' \
  git ls-remote git@github.com:korrektness/jormungandr.git HEAD
```

5.02 s, exit 128: `ssh: connect to host github.com port 22: Connection timed out`, then `fatal: Could not read from remote repository.`

To distinguish blocked port 22 from missing credentials, tried GitHub's SSH endpoint on port 443, using a scratch-only trust-on-first-use known-hosts file:

```sh
/usr/bin/time -p timeout 15 env GIT_TERMINAL_PROMPT=0 \
  GIT_SSH_COMMAND="ssh -o BatchMode=yes -o ConnectTimeout=5 -o Hostname=ssh.github.com -p 443 -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$R/github-known-hosts" \
  git ls-remote git@github.com:korrektness/jormungandr.git HEAD
```

0.72 s, exit 128. Host key accepted into the temporary file; response: `git@ssh.github.com: Permission denied (publickey).` Port 443 was reachable, but the account offered no accepted GitHub identity. This failed before repository authorization.

### Access restored and checkout

The dispatcher obtained operator approval and registered oliphaunt's key with GitHub, then instructed this session to resume through port 443 without editing `~/.ssh/config`. A plain clone still failed with `Host key verification failed.` (0.64 s): GitHub authentication and local host trust are separate requirements. Retried with the scratch known-hosts file from the earlier successful port-443 handshake:

```sh
# Second phase's raw logs; the first phase's $R is unchanged elsewhere in this log.
R2=/tmp/bot-run.bww2aXERQl
cd ~/dev
/usr/bin/time -p env \
  GIT_SSH_COMMAND='ssh -o BatchMode=yes -o ConnectTimeout=5 -o UserKnownHostsFile=/tmp/bot-run.eFGT3r3Vbg/github-known-hosts' \
  git clone ssh://git@ssh.github.com:443/korrektness/jormungandr.git \
  > "$R2/jormungandr-clone-retry.log" 2>&1
cd jormungandr
git rev-parse HEAD
```

Succeeded in 3.89 s. Commit: `442ff007d9d3dcdfe6e389c0cfe93c3cd40738d5`. No SSH config or default known-hosts file was edited by this session.

During the build/test run, the dispatcher reported that outbound port 22 was opened in oliphaunt's ufw firewall and that `git@github.com` now authenticates. This was a separately administered change, not a user-local install step. Confirmed private repository access over port 22 after the test run:

```sh
/usr/bin/time -p env \
  GIT_SSH_COMMAND='ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/tmp/bot-run.bww2aXERQl/github-port22-known-hosts' \
  git ls-remote git@github.com:korrektness/jormungandr.git HEAD
```

Succeeded in 1.49 s, returning the same `442ff007…` HEAD. No keys/tokens were copied, generated or registered by this setup session. Private access is no longer blocked.

### README build and tests

Read jormungandr's README and linked `docs/developing.md`; they call the project SnaKt and prescribe `publishToMavenLocal` and `./gradlew test`. Used the same local JDK 21 and installed Z3 4.8.7 as the public SnaKt trial. The README also documents automatic Z3 provisioning for tests when an absolute `Z3_EXE` file is not provided; that fallback was not needed.

```sh
source ~/.local/opt/benchmark-env.sh
export Z3_EXE=/usr/bin/z3
cd ~/dev/jormungandr
/usr/bin/time -p ./gradlew publishToMavenLocal > "$R2/jormungandr-publish.log" 2>&1
/usr/bin/time -p ./gradlew test > "$R2/jormungandr-test.log" 2>&1
```

No Silicon-fork init script, dependency override, golden regeneration, or repository patch was used. Gradle 8.14.3 reused the earlier tool/dependency caches.

| Command | Wall seconds | Result |
|---|---:|---|
| `./gradlew publishToMavenLocal` | 36.08 | BUILD SUCCESSFUL, 47 tasks executed / 1 from cache |
| `./gradlew test` | 502.53 | BUILD FAILED, exit 1; 23 executed / 32 up-to-date |

JUnit XML totals:

| Module | Tests | Failures |
|---|---:|---:|
| compiler plugin | 522 | 1 |
| test runner | 20 | 0 |
| uniqueness | 7 | 0 |
| core | 13 | 0 |
| locality | 34 | 0 |
| Total | 596 | 1 |

No errors or skipped tests were reported. The failing case is `PhasedDiagnosticTestGenerated$Targets$Arraydeque.testArrayDeque()` (176.947 s). Its XML reports three golden mismatches: `ArrayDeque.viper.diag.txt`, `ArrayDeque.kt`, and `ArrayDeque.trust.txt`. The log does not establish their root cause; no expected-output updates or reruns were used to hide the failure. Detailed HTML report: `~/dev/jormungandr/formver.compiler-plugin/build/reports/tests/test/index.html`; XML: `formver.compiler-plugin/build/test-results/test/TEST-org.jetbrains.kotlin.formver.plugin.runners.PhasedDiagnosticTestGenerated$Targets$Arraydeque.xml`.

Build warnings concern deprecated Kotlin symbol APIs; test compilation warns about a K1 API scheduled for deprecation. During JVM shutdown the test log also prints `java.io.IOException: java.lang.IllegalStateException: Shutdown in progress` from test-runner `Lifetime.closeResources`, with Silicon teardown attempting `Runtime.removeShutdownHook`. This cleanup exception is separate from the recorded golden mismatch; causality was not investigated in this setup exercise. The working tree remains clean.

Publishing jormungandr also writes the local Maven repository used by SnaKt. The 171-test public SnaKt result above was obtained before that publication; it was not rerun afterward.

## 4. Corpus and U/S task: works

The dispatcher authorized proceeding out of order while it handled private access. Read the copied harness README and the repository's [harness.md](../../harness.md). These are external experiment scripts rather than a standalone installable package.

From the source host, created matching directories on oliphaunt with `ssh oliphaunt 'mkdir -p …'`, then transferred:

```sh
rsync -a ~/data/viper-corpus/ oliphaunt:data/viper-corpus/
rsync -a --exclude __pycache__ \
  ~/data/viper-corpus-results/evening-20261028/harness/viper_corpus \
  ~/data/viper-corpus-results/evening-20261028/harness/solver_cpu_benchmark.py \
  ~/data/viper-corpus-results/evening-20261028/harness/z3-cpu-wrapper.sh \
  oliphaunt:data/viper-corpus-results/evening-20261028/harness/
rsync -a ~/data/viper-corpus-results/dagrea-20261029/replication/{run.sh,paired.py,logged.py,profile.py,manifest.jsonl} \
  oliphaunt:data/viper-corpus-results/dagrea-20261029/replication/
rsync -a ~/data/viper-corpus-results/nightly/jars/ff386aec4664231dc6f2cb56d8d35f1623088363/silicon.jar \
  oliphaunt:data/viper-corpus-results/nightly/jars/ff386aec4664231dc6f2cb56d8d35f1623088363/
rsync -a ~/data/viper-corpus-results/evening-20261028/jars/S/silicon.jar \
  oliphaunt:data/viper-corpus-results/evening-20261028/jars/S/
```

Transfer times, respectively: 49.11, 0.40, 0.31, 5.37, 5.02 s (source shell's `time`). A first attempt to time these with `/usr/bin/time -p` failed before any transfer because the source host lacks that executable; retried using the shell keyword. This friction is source-host-only. The corpus occupies 378 MiB on the source. A checksum dry run, `rsync -acni ~/data/viper-corpus/ oliphaunt:data/viper-corpus/`, reported no differences (0.58 s). All 14 replication manifest entries also passed explicit size/SHA-256 checks against the remote corpus.

Existing experiment JARs were copied, not rebuilt or fetched from upstream. Source and destination SHA-256 match:

- U (`ff386aec…`): `1c2f5a902d3ffa19505b260dadfcb81feaeb8dfd94fa6be3b09e5482c94bfcef`.
- S (store-lookup arm): `72a0fb7b6c9c5856a8db26e3b680d582cef7080af3f45ee4ddfbd6aed1e93991`.

Created `replication/smoke-manifest.jsonl` by selecting `DaG/gobra/dafny_book/chap01.vpr` from the transferred manifest (preserving its metadata). Ran the existing `paired.py` adapter, including its hard-coded independent A control, which uses the same JAR as U:

```sh
source ~/.local/opt/benchmark-env.sh
O=$HOME/data/viper-corpus-results/dagrea-20261029/replication
systemctl --user set-property silicon.slice MemoryMax=20G MemorySwapMax=0
/usr/bin/time -p python3 "$O/paired.py" \
  --corpus-root "$HOME/data/viper-corpus" --manifest "$O/smoke-manifest.jsonl" \
  --output-directory "$O/smoke" --java "$JAVA_HOME/bin/java" --z3 "$Z3_EXE" \
  --lanes '2,10' --lane-memory-max 7G --jvm-arg=-Xmx4g \
  --verifier-arg=--proverArgs --verifier-arg=memory_max_size=3000 \
  --seeds 0 --window any --timeout 60 \
  --arm "U=$HOME/data/viper-corpus-results/nightly/jars/ff386aec4664231dc6f2cb56d8d35f1623088363/silicon.jar" \
  --arm "S=$HOME/data/viper-corpus-results/evening-20261028/jars/S/silicon.jar" \
  --arm "A=$HOME/data/viper-corpus-results/nightly/jars/ff386aec4664231dc6f2cb56d8d35f1623088363/silicon.jar" \
  > "$R/corpus-smoke.log" 2>&1
```

Succeeded in 7.59 s overall. All three seed-0 records are `verified` in `replication/smoke/results.jsonl`:

| Arm | Wall s | Total process CPU s | Solver CPU s |
|---|---:|---:|---:|
| A | 2.072 | 3.80 | 0.03 |
| U | 2.111 | 3.92 | 0.04 |
| S | 2.092 | 3.87 | 0.03 |

This confirms execution/accounting, not a speedup. The systemd user manager and scoped memory limits worked without sudo. Set the shared slice to 20 GiB rather than the README's 40 GiB example because this host has only 31 GiB RAM; each runner retains the documented 7 GiB limit. This user-manager setting remains in place.

Portability friction: `paired.py` imports from an absolute `/home/kameliya/data/viper-corpus-results/evening-20261028/harness/viper_corpus` path, so replication scripts alone are insufficient. The copied `run.sh` additionally assumes `~/.sdkman/candidates/java/21.0.10-tem`, nightly `inputs/z3`, and nightly `inputs/profile.json`; those paths were not installed/copied. Used explicit local Java/Z3 paths, a single seed, and `--window any` for the smoke instead. The full unmodified eight-seed replication launcher is not ready to run; it needs those paths adapted and the profile transferred. Existing run outputs/analysis were not copied.

## Handoff and documentation friction

- Public HTTPS works without account setup. GitHub port-22 SSH initially hung, and private access additionally required key registration. Both access issues are now resolved; the initial commands/errors above record the newcomer friction.
- Silicon's short recipe leaves JDK/Z3/sbt installation and example selection to the newcomer. Its README and performance summary disagree on Java 11 versus 21. A user-local installation recipe and a checked-in example command would remove these decisions.
- SnaKt's Gradle wrapper handles the missing build tool automatically. Its documented test command and existing Z3 4.8.7 worked without changes; keep its solver selection separate from the Silicon benchmark environment.
- jormungandr needs no undocumented build workaround, but its documented test command is not green: one golden-comparison failure and a shutdown cleanup exception need separate investigation.
- `/usr/bin/java -version` still reports 25.0.4.1 and `/usr/bin/z3 --version` still reports 4.8.7. All three working trees are clean. Local installations and Maven/Gradle/sbt caches remain in the account. The only system configuration change reported is the dispatcher's outbound-firewall fix; this session changed only user-local files/settings.

Elapsed remote exercise was approximately 30 minutes including documentation reading, network diagnosis, operator-assisted access repair, and result checks, with log drafting interleaved. Timed downloads, clones, assembly/example, publish, tests, transfers and smoke account for approximately 22 minutes. No access blocker remains. The corpus smoke works; a full replication still needs launcher path adaptation, and jormungandr's default suite has one unresolved failing test.
