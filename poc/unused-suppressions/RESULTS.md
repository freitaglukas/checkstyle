# Recorded PoC results

Executed 2026-10-18 with JDK 21.0.12.1, Maven wrapper 3.10.0 and Python 3.14.
Base: `282a3360a4acb991dd35e24421113f5a67f7bb98`.

| Check | Observed result |
| --- | --- |
| Baseline focused build | 66 tests; no failures, errors or skips; build exit 0 |
| Prototype focused build | Same 66 tests; no failures, errors or skips; build exit 0 |
| `before` | 4 assertions / 3 fresh JVMs; run-wide accounting falsely flags the cached useful comment; no-cache control stays quiet |
| `after` | 20 assertions / 31 fresh JVMs; real cold/warm hits, stale/mixed markers, source/config/check/selector/external-resource changes, severities, ordinary diagnostics and parse policy |
| `edges` | 5 observations / 10 fresh JVMs; overlap and other-filter order stay quiet; differing-selector instances reproduce the OPEN LIMITATION |
| Stock control | Expected assertion failure, exit 1: no stale-marker report on the unchanged engine |
| Hook-disabled control | Expected assertion failure, exit 1: omitting only the new callback removes the stale-marker reports |
| Restored prototype | `after` passes again, exit 0 |

The differing-selector observation is a reproduced design problem, not an accepted
feature result. The broader provider/identity solution is proposed in DESIGN.md;
it is not implemented by this branch. The same applies to original IGNORE evidence
eligibility, global declarations and the other suppression modules.

`prepare.py` records build commands, exit codes and engine hashes. `reproduce.py`
records each process, source/config fingerprint, diagnostic, processed-file list
and cache snapshot in ignored local evidence directories. Reproduction does not
depend on trusting these summary statements.

Full `clean verify`, complete API compatibility and all project contribution gates
have not been run for this experimental branch. No production PR is proposed.
