# Unused suppression PoC and full-issue design

Experimental branch for [#3242](https://github.com/checkstyle/checkstyle/issues/3242),
based on `282a3360a4acb991dd35e24421113f5a67f7bb98`. This is for design discussion,
not a production-ready PR or a claim that the whole issue is fixed.

The [design proposal](DESIGN.md) covers all nine suppression modules, shared
configuration, partial scans, late diagnostics and cache alternatives. Only the
NearbyComment path is implemented here. The public API is provisional.

## What the executable proof demonstrates

| Same useful suppression and unchanged configuration | Run-wide tracker | Per-file hook |
| --- | --- | --- |
| Cold cache | No warning; file cached | No warning; file cached |
| Warm cache | False unused warning; source skipped | No warning; genuine cache hit |

The run-wide tracker is an experimental implementation of the concern described
in the issue. Stock Checkstyle does not have this unused-suppression feature.

The per-file implementation matches recognized comments against original
TreeWalker violations after checks finish. It also discovers comments in files
with no violations, then emits new findings after ordinary TreeWalker filtering.
Normal Checker filters/cache eviction still apply. A genuinely stale comment is
reported on both cold and warm runs. Removing the hook makes that proof fail.

## Reproduce

Requirements: JDK 21+ (`JAVA_HOME` recommended), Python 3 and the repository's Maven
wrapper. The builds may download normal Maven dependencies. No GitHub credentials
or API calls are used. The commands below create a dedicated baseline worktree;
they do not reset your current checkout.

From this branch's repository root:

```sh
git worktree add --detach ../checkstyle-3242-baseline 282a3360a4acb991dd35e24421113f5a67f7bb98
python3 poc/unused-suppressions/prepare.py --baseline ../checkstyle-3242-baseline
python3 poc/unused-suppressions/reproduce.py --phase before
python3 poc/unused-suppressions/reproduce.py --phase after
python3 poc/unused-suppressions/reproduce.py --phase edges
```

Preparation builds both engines with the existing focused tests, snapshots original
uninstrumented classes, derives the dependency classpath from Surefire's report and
compiles the small experiment. Every audit starts a fresh JVM. Each phase gets a
fresh evidence directory, with cold/warm runs sharing a real persistent cache.

Expected controls, each exiting **1** for a missing-diagnostic assertion:

```sh
python3 poc/unused-suppressions/reproduce.py --phase red
python3 poc/unused-suppressions/reproduce.py --phase after --engine poc/unused-suppressions/engines/hook-disabled.jar
```

The stock control cannot report a stale comment. The other control uses the same
prototype with only the final report callback omitted. The normal `after` phase
must pass again when that engine override is removed.

Generated logs, cache/config snapshots, hashes, classes and engine jars stay in
ignored directories under this folder. No compiled binaries or private logs are
part of this branch. [The recorded summary](RESULTS.md) separates passing proofs
from the reproduced open limitation.

## Limits

The `edges` phase intentionally reproduces a remaining problem: two filter
instances recognizing the same marker but using different selectors can disagree
about usage. Its success means that limitation was reproduced, not that it is
acceptable production behavior. The design replaces per-instance reporting with
shared physical-declaration usage. It also covers overlapping regex-span ambiguity.

Global XML/Single/XPath, paired comments, text filters and annotations are design
work only. The existing 66 focused tests and the small integration experiment are
not full project validation. Source/binary compatibility, documentation versioning,
all locales, complete provider coverage, regression diffs and `clean verify` remain
requirements before a production PR. Default filtering and fixture bodies are
preserved; fixture config headers explicitly declare the two new defaults and
their existing diagnostic references move by two lines.
