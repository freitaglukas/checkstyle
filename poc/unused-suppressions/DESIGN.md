# Design proposal for the full scope of #3242

This is a proposal for discussion, not implemented coverage. The executable PoC
implements only opt-in nearby-comment reporting and still has the multi-instance
limitation reproduced by `reproduce.py --phase edges`.

The goal is to report suppression declarations that **match no original violation
under a stated configuration and audit scope**. This is not a proof that removing
the declaration is safe in every project, configuration or future version.
Reporting stays off by default.

## Coverage

| Modules | Reporting unit | Evidence and completion needed |
| --- | --- | --- |
| `SuppressWithNearbyCommentFilter` | Each recognized source marker | Original TreeWalker violations after both walks and `finishTree`; complete successful parse |
| `SuppressionCommentFilter` | Each recognized OFF declaration/region | Same source evidence, evaluated using the existing nearest matching OFF/ON state |
| `SuppressWithPlainTextCommentFilter`, `SuppressWithNearbyTextFilter` | Each OFF region or nearby text marker | Original Checker/FileSet and TreeWalker evidence, including late emissions |
| `SuppressWarningsFilter` with `SuppressWarningsHolder` | Each Checkstyle annotation value | Frozen annotation locations/ranges/aliases and original evidence for that file |
| `SuppressionSingleFilter`, `SuppressionFilter` | Each configuration occurrence or XML element | Complete owned input scope, all relevant original violations, final producer barrier |
| `SuppressionXpathSingleFilter`, `SuppressionXpathFilter` | Each configuration occurrence or XML element | Complete owned scope plus original TreeWalker violations evaluated with their ASTs |

This covers all nine built-in suppression modules. General severity gates,
before-execution exclusions and check-specific ignore options are not automatically
suppression declarations. Custom suppression providers need an explicit opt-in
contract; an opaque boolean `accept` implementation cannot expose its inventory.

## Shared inventory and usage

Keep normal filtering unchanged. Add a separate audit service which inventories
declarations and observes original diagnostics before filtering. Providers expose
side-effect-free matching based on the existing predicates. Do not replay arbitrary
`accept` calls or remove short-circuiting from the normal filter chains.

Separate these identities:

- Physical declaration: source/resource snapshot and exact marker/value/element
  span. Equal text at two locations remains two declarations.
- Interpretation: that declaration plus its configuration-tree occurrence and
  resolved selectors, range and provider semantics. Check names, aliases and
  configured check IDs are selectors, not unique identities.

Union usage across interpretations of the same physical declaration, including
participating consumers with reporting disabled. Report once only if no consumer
can match an eligible original violation. This resolves the PoC's differing-selector
instance case. Severity/message policy belongs to the central reporter.

Proposed eligible evidence excludes original `SeverityLevel.IGNORE` diagnostics,
including when ignored modules are executed. This is separate from the severity of
the new unused findings. The PoC does not yet implement or prove that distinction;
the accepted policy needs explicit tests before a production increment.

Exact spans allow safe identity sharing. Arbitrary regexes yielding different
spans cannot automatically be declared equivalent. Providers need a canonical
origin/grouping contract for that case; ambiguous ownership must be reported as
unknown or explicitly excluded, rather than producing a confident unused finding.

Both overlapping nearby comments can be relevant to one violation, even when
another suppression rejects it first. This intentionally misses some redundant
suppressions. For OFF/ON filters, reuse the actual nearest matching state; do not
replace it with a nesting stack or assume every ON must suppress something.
Proposed reporting unit: ineffective OFF declarations, with ON treated as a
terminator. Orphan ON validation is a separate policy decision.

## Evidence lifecycle

TreeWalker supplies original violations after both walks and all `finishTree`
calls, while AST context is still available for XPath. Checker also supplies
original FileSet results and direct dispatcher emissions before Checker filtering.
A Checker-only observer misses violations already removed by TreeWalker filters.

Checker-level text and annotation providers retain immutable per-file inventories
until late events are complete. They cannot consult the last processed file's lazy
cache or thread-local annotation list. Carry absolute file identity separately from
the displayed/basedir-relative path; use the same decoded snapshot as the matching
provider. Existing charset, filename, XPath token/character-column, line/column OR,
range and first-match recognition semantics must remain explicit compatibility
constraints. Additional matches discovered by a new scanner are not active
declarations unless normal filtering also recognizes them.

Checker needs a terminal audit barrier after **both** `finishProcessing` and
`destroy` of every diagnostic producer, before final status/count and audit output
close. Both lifecycle methods may currently log. An ordinary FileSetCheck placed
last in configuration is insufficient. Audit findings never become usage evidence.

Annotation reporting initially targets explicit `checkstyle:` values. Unprefixed
`unchecked`, `deprecation` or `all` may be needed by javac or another tool. Lack of
Checkstyle matches does not establish that those values are removable. Preserve
individual array values and source spans, and the Holder's actual alias/range and
expression-resolution limits. Broader annotation reporting needs a separate policy.

## Cache strategy

For processed source-local declarations, retain the demonstrated behavior: no
inventory/inference for cache-skipped files, and accepted unused findings against
the source file prevent it from remaining cached. A Checker-level final report must
still precede cache persistence. Late/global producers cannot be assumed complete
merely because a source-local producer was skipped successfully.

For global declarations, recommend a **dedicated complete uncached audit** first.
Its declared scope supplies every relevant producer with every accepted input.
Do not read, clear or overwrite the ordinary timestamp cache. A warm ordinary
cache must not suppress work in this audit, and normal incremental runs retain
their existing performance. XPath uses live AST matching; a node selected by a
query is not itself a matching violation.

An incremental alternative needs a new versioned usage ledger. Each successful
file record contains matching declaration identities, an explicit empty-result
record and completeness evidence. Cache hits replay those records. Missing,
corrupt or incompatible records require recomputation or an incomplete result.
Never interpret missing data as zero usage or retain yesterday's positive witness
after a file is removed. Fingerprints include source snapshot, engine/custom check
implementation, effective configuration, locale/path context and declared external
resources. Changed XPath queries require fresh AST evaluation.

The ledger does not solve multi-file producers: another file's edit/deletion can
change their results. They need complete input/dependency-group processing, using
or coordinating with the existing proposals in [#3539](https://github.com/checkstyle/checkstyle/issues/3539#issuecomment-6158692950)
and [#3540](https://github.com/checkstyle/checkstyle/issues/3540). A separate uncached
audit avoids depending on an incremental ledger for the first global implementation;
it does not fix ordinary cached TranslationCheck execution.

## Scope, incomplete runs and reporting

Use three states: `MATCHED`, `UNMATCHED`, `UNKNOWN`. One positive witness establishes
`MATCHED`. `UNMATCHED` requires zero witnesses **and complete relevant coverage**.
Parse/check/IO/finalization failures, omitted owned inputs, opaque relevant producers
or missing replay records produce `UNKNOWN`. Strict auditing returns an explicit
incomplete status/error and withholds unsupported unused findings; it must not
silently pass or disguise missing evidence as an unused declaration.

The caller supplies the intended scope and exclusions; Checker must not silently
expand a changed-files invocation to the whole repository. `files` regexes describe
event matching, not ownership. Gating unused reports on an observed filename would
hide rules for deleted files. With a complete owned scope, distinguish no target
file, no enabled matching rule and no matching violation. Disabled-rule results
describe the current profile, not every other profile using the configuration.

Shared configurations need explicit declaration ownership or an aggregate audit
covering all intended project/configuration scopes. One local zero cannot establish
global zero. Aggregation needs namespaced source paths and a complete receipt from
every required scope; missing projects mean unknown. Without that ownership or
coverage, leave shared declarations unaudited rather than reporting false staleness.

Preserve XML/config declaration origins before runtime sets deduplicate equal
values. XML entries need resource URI, locator and occurrence; Single declarations
need their configuration location or stable module-path fallback. Final findings
belong to those origins, through balanced audit output events. They must not remove
the suppression resource from the source cache or invalidate all cached sources.
Optional absent resources yield zero loaded entries; invalid required resources
keep their existing setup errors. The final audit channel excludes audited
declarations from suppressing their own findings; explicit meta-report filtering
requires a documented policy.

## Implementation sequence and acceptance

Design the whole feature first, then propose independently reviewable increments:

1. Shared declaration identity/usage union and a corrected NearbyComment path,
   including legacy/lambda API compatibility, translations/docs and full validation.
2. Remaining source-local providers, annotation ownership and Checker raw-event/
   final-barrier support, with late-producer and cache tests.
3. Complete-scope uncached XML/Single/XPath audit, shared-scope ownership/aggregation,
   origin-aware output and incomplete-audit status.
4. Optional incremental ledger only after completeness/dependency contracts agree.

Required comparisons include cold/warm versus uncached reference; deleted last
witness; source/config/check/locale/resource changes; same-marker differing
instances including non-reporting consumers; overlapping/cross-module declarations;
OFF/ON selector/state boundaries; original IGNORE versus reporter severity;
annotation arrays/compiler-only values; exact
columns/tabs/Unicode; zero diagnostics/checks; XML duplicate elements; late finish/
destroy emissions; partial/failed/shared scopes; and legacy/custom-provider behavior.
Negative controls must remove observation/replay/finalization to show the tests fail.

Full issue completion requires accepted semantics and implemented coverage for all
agreed providers, with matching outputs/default behavior preserved, project regression
diffs and full required validation. The current PoC is evidence for one path only.

## Decisions requested

1. Is “matches an original violation” the intended meaning, accepting conservative
   treatment of redundant overlapping suppressions?
2. Is a dedicated complete uncached audit acceptable for global declarations first,
   with explicit ownership/aggregation for shared configurations?
3. Is the shared provider/inventory service preferable to making the PoC's two
   TreeWalkerFilter methods permanent public API?
4. Agree OFF/ON reporting units, annotation ownership, incomplete status and final
   diagnostic filtering before choosing the first production increment.

This follows the [original cache/lifecycle concern](https://github.com/checkstyle/checkstyle/issues/3242#issuecomment-254271049),
the [scope/shared-config guidance](https://github.com/checkstyle/checkstyle/issues/3242#issuecomment-3008993039)
and the [request for an executable PoC](https://github.com/checkstyle/checkstyle/issues/3242#issuecomment-5014629669).
