# Automatic merged-PR draft APKs

Every successful `Build` push run on `huteri/visor` `main` calls `release.yml`.
The caller waits for all Build tests and the preview build to succeed. No separate
privileged `workflow_run` checkout or downloaded PR artifact is used. The callee
uses the caller's exact canonical push SHA, never the PR head. Fork PRs still
build unsigned previews without secrets. Direct main pushes, dispatch builds,
unmerged PRs, and non-main pushes do not create automatic releases.

Before any release signing secrets are exposed, `scripts/auto_release.py` checks
main ancestry and the commit-associated PR API: closed, merged, base repository
`huteri/visor`, base `main`, and `merge_commit_sha` exactly equal to the push SHA.
Association metadata is retried for 50 seconds. Empty/nonmerge results skip;
API and ancestry failures fail closed. Rerun failed Build jobs after transient
API failures. Existing PRs are not merged or backfilled by this change.

## Versions and retries

Android codes retain the existing `major * 10000 + minor * 100 + patch` mapping.
The first automatic candidate is **1.3.9 / 10309**, above distributed code 10308
and checked-in default 10307. All fork draft releases, published releases and
version tags count when selecting the next version. A larger existing version
wins. Base-100 rollover is explicit: 1.3.99 becomes 1.4.0. Nonrepresentable tags
(minor or patch >= 100) fail rather than alias another Android code.

An atomic lightweight version tag reserves each merged SHA before building.
Concurrent merges race to create a tag; losers refresh and increment. Each SHA
has its own Actions concurrency group: no global one-pending-run queue can drop
intermediate merges. Version ordering follows reservation order, not necessarily
merge time. A failed build retains its reservation; rerunning the same SHA reuses
that version. A draft with the same tag and exact target gets assets replaced;
a published release or changed target fails closed. Do not delete reservation
tags: they prevent version reuse. Manual releases should choose an unused version
above existing reservations; simultaneous manual use of an auto-selected version
is not supported. No huge run-number-based versionCode jump is introduced.

Tags created with the workflow's `GITHUB_TOKEN` do not trigger tag-push workflows.
Automatic releases are always **draft**, even though their caller event is push.
The existing independent manual-dispatch draft and explicit tag-push public
release behavior remains. Publishing a draft requires a separate authorized
human action. Draft APKs require repository write access to download.

## APK gates

All six established signing/Meta secrets must exist; missing secrets fail.
The release build runs parser unit tests and Python regression tests, builds the
signed APK, verifies its cryptographic signature and certificate against the
restored established keystore, checks embedded version metadata, and includes a
SHA-256 checksum asset. Always-run cleanup removes restored keystore and temporary
certificate files, including on failure. No signing material is committed.

Build's PR/main **preview** remains independently fixed at `1.3.7-ciRUN / 10307`;
Gradle's no-environment default remains `1.3.7-spokenprogress.1 / 10307`. Neither
is the automatic draft release version. Preview comments explicitly warn they
cannot update distributed 10308+ or automatic drafts. Release builds override
both Gradle variables with the allocated version. Do not uninstall a working
release merely to install an older preview.

Local workflow validation:

```sh
python3 -m unittest discover -s scripts/tests -v
actionlint
git diff --check
```

A real signing/upload run needs merged workflow code and configured GitHub secrets;
local logic tests do not claim a delivered APK or hardware acceptance.
