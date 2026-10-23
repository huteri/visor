# Context continuity — approved local release source

Branch `fix/hermes-context-continuity`; isolated worktree `/home/huteri/visor-context-continuity`.
Fetched origin/main base and HEAD `113cbd5b17449d2cb5df815efe9c41be84a78bc8`. Changes uncommitted; no push or PR. Existing progress/resolution dirty worktrees not edited.

## Changes

- Carry logical diary conversation via coroutine context without changing `Brain.answer` mock ABI.
- Capability opt-in from authenticated `/v1/capabilities` feature `session_continuity_header`; generic providers retain last-12 client history.
- Scope opaque server ID by endpoint, hashed credential, model, vision preference and logical conversation. Persist in diary `sessions.json`; never store raw key.
- Capture successful JSON/SSE response header before parsing body. Bootstrap full diary text history once, then send system/current user only. New bootstrap gets a unique prompt discriminator, not an invented server ID.
- Existence GET `/api/sessions/{id}` before continued POST because installed Hermes accepts unknown IDs with empty history. Fail closed on unavailable session; no blind replay. Existing field/tool negotiation disabled for continued/acknowledged sessions.
- Fresh wake honors configured memory window. Live AI first question resumes latest recent logical conversation, then pins session; exact English utterance `new conversation` starts new logical conversation. Phone composer retains existing explicit-new UUID behavior.
- One latest prior persisted photo, maximum 2 MiB, included only while vision allowed; missing newest file does not resurrect older photos. Live voice-only uses separate server mapping. Existing photo storage policy unchanged; no new image persistence.
- Any diary deletion clears session mappings so future bootstrap cannot reload deleted server transcript. Server-side data is not deleted.
- Local fallback version 10407 / 1.4.7-context.1; unchanged package `com.macedonga.visor`.

## Executed evidence

All logs `/home/huteri/.hermes/cache/scratch/`.
Shared lock `visor-gradle.lock`; Gradle heap 768 MiB, one worker, in-process Kotlin, JVM tests 256 MiB / one fork. Final packaging metaspace 512 MiB after 384 MiB exhausted.

Canonical wrapper `context-tests.sh` invokes:
`./gradlew <tasks> --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx768m -XX:MaxMetaspaceSize=512m' -Pkotlin.compiler.execution.strategy=in-process -I /home/huteri/.hermes/cache/scratch/visor-budget.init.gradle`

RED/GREEN:
- `context-red1-runtime.log`: session test fails missing echoed continuation; `context-green1.log` passes.
- `context-red2.log`: >12 history bootstrap assertion fails; `context-green2.log` passes.
- `context-red3.log`: real Assistant fresh-wake record chooses new conversation after five minutes; `context-green3.log` passes.
- `context-red4.log`: Live resume policy fails; later policy suite green.
- `context-red5.log`: reference-image selection fails; later full suite green.
- Initial full run `context-full1.log`: 21 failures caused by newly invoked unstubbed Diary method in LiveAiPreambleTest, including cascading uncaught exception. Added only missing fixture stub. `context-full2.log` passes.
- Final `context-final-tests.log`: `:core:assistant:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL. Fresh JUnit totals 127 tests, 0 failures, 0 errors, 1 skipped (126 passed).
- `context-gates2.log`: tests/debug assembly finish, touched-module lint blocked by dependency `:core:media:lintAnalyzeDebug`, lint internal OutOfMemoryError analyzing Airship.kt. No lint pass claimed.
- `context-release.log`: final `:app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease` BUILD SUCCESSFUL. Established signing/private app constants injected privately by scratch `context-release.py`.
- `git diff --check` passes.

Historical release artifact predates blocker fixes below; NOT rebuilt or approved:
`/home/huteri/visor-context-continuity/app/build/outputs/apk/release/app-release.apk`
Size 65796905 bytes. SHA-256 `0ed7241e667cb1643c3b1951d25f93635161ed10b9c884ff0263a4b6e416f55a`.
`apksigner verify --print-certs` succeeds; certificate SHA-256 `21aec828a184d633e15c2e7d7e5a8b2b86db3943abb50ba82d408a8979574e0e`.
ZIP test passes. aapt verifies package/version/Visor label/MainActivity.

## Spec-review blocker fixes (current source)

- Deletion increments diary generation under same monitor as capture. Outstanding contexts/captures fail closed; delayed headers cannot recreate removed mapping. Validate before POST, during reads, before tool handling and negotiation retry.
- Coroutine mutex serializes same diary/conversation across Brain instances, including first bootstrap, history selection, header persistence and completed-history publication. Cancellation unlocks in finally; other conversations run independently. Assistant phone completion is published inside lock; glasses/Live replies published before lock release.
- `askAgain` carries replay intent without clearing prior answer/actions. Hermes replay rejected before existence GET/POST with explicit visible problem. Generic retry still runs. Fixture models server tool side effect followed by either successful answer or malformed/lost response; each stays at one POST/side effect after retry.
- Session mapping count capped at 128; capture I/O already runs on Dispatchers.IO. Conservative deletion invalidates all mappings.

TDD exact commands (prefix each with `bash /home/huteri/.hermes/cache/scratch/context-tests.sh`):

| Phase | Arguments | Evidence / result |
|---|---|---|
| RED deletion | `:core:assistant:testDebugUnitTest --tests '*BrainSessionTest.deletedConversationRejectsDelayedSessionHeader'` | `blocker1-red.log`: 1 test, 1 assertion failure |
| GREEN deletion | same | `blocker1-green.log`: success |
| RED concurrent bootstrap | `:core:assistant:testDebugUnitTest --tests '*BrainSessionTest.concurrentBootstrapContinuesFirstTranscript'` | `blocker2-red.log`: 1 test, 1 failure (two bootstraps) |
| GREEN concurrent bootstrap | same | `blocker2-green.log`: success |
| RED replay | `:core:assistant:testDebugUnitTest --tests '*BrainSessionTest.echoedJsonSessionSurvivesBrainRecreationAndDoesNotReplayHistory'` | `blocker3-red.log`: 1 test, 1 failure (no Retry/Again refusal) |
| RED metadata cap | `:core:assistant:testDebugUnitTest --tests '*BrainSessionTest.sessionMetadataHasBoundedRetention'` | `metadata-red.log`: 1 test, 1 failure |
| GREEN full regression + debug | `:core:assistant:testDebugUnitTest :app:assembleDebug` | `blockers-final.log`: BUILD SUCCESSFUL; XML totals 132 tests, 131 passed, 1 skipped, 0 failures/errors |

`git diff --check` passes. No release rebuild, push, server change or experimental-worktree edit during blocker fixes. Historical release above is stale. Parent must rerun spec review, then quality review before release build approval.

## Canceled-header ownership blocker (current source)

- Each scoped HTTP POST owns an opaque `Diary.SessionRequest`. New request supersedes old owner. Cancellation synchronously revokes ownership before asynchronous disconnect; worker completion removes only its own token.
- Ownership check, deletion-generation validation and session persistence use the same Diary monitor. A canceled worker returning from blocked `responseCode` cannot publish a late header, even without replacement request; old cleanup cannot revoke newer request.
- Deterministic production `Brain.answer`/`post`/`request` tests block response headers while `disconnect()` deliberately does nothing. A cancels, B bootstraps/captures `new-session`, A returns late; final mapping remains B and C sends B's ID. Separate case asserts canceled A cannot create mapping without B.
- Shared `TestHttpConnections` URL factory avoids collision with existing `BrainHttpLeaseTest` process-wide factory; existing image revocation tests retain production path.
- RED: `bash /home/huteri/.hermes/cache/scratch/context-tests.sh :core:assistant:testDebugUnitTest --tests '*BrainSessionTest.cancelled*'`; `cancelled-header-red.log`: 2 tests, 2 expected assertion failures (canceled mapping persisted; B overwritten).
- GREEN: same command; `cancelled-header-green.log`: BUILD SUCCESSFUL.
- Final: `bash /home/huteri/.hermes/cache/scratch/context-tests.sh :core:assistant:testDebugUnitTest :app:assembleDebug`; `cancelled-header-full.log`: BUILD SUCCESSFUL. Fresh XML totals **134 tests, 133 passed, 1 skipped, 0 failures/errors**. Same 768 MiB Gradle / 256 MiB tests / one worker budget.
- `git diff --check` passes. No commit, push, release rebuild, server change or other-worktree edit. Historical release remains stale. No device/server acceptance claimed.

## Review gaps / limitations

- No live authenticated Hermes/device acceptance; documentation extraction tool unavailable. Installed server source establishes header/history/existence semantics; hermes_state_messages.py restores decoded multimodal content, so continued Hermes requests do not replay prior image bytes.
- Tests cover JSON/SSE, Brain recreation, bootstrap >12, new/expired IDs, changed credential, auxiliary isolation, generic last-12/retry regression, fresh wake, cutoff, deterministic Live pin/new, image bound and vision-off/deletion; added delayed-header deletion, same-scope concurrent bootstrap, cancellation/different-scope lock behavior, server-tool replay refusal and metadata bound. No actual clock-minute injection, endpoint-switch integration test, or on-wire Claude photo fixture yet.
- Session metadata capped at 128 mappings. Header capture/read/write runs on Brain IO context; deletion still synchronously removes the small metadata file. Deletion clears all mappings conservatively. Locks scoped per diary/conversation; unused conversation lock entries retained until diary GC.
- Existence GET and later POST are not atomic; server deletion between them cannot be solved fully client-side without server contract changes.
- Live AI reset command English-only and still goes through normal answer handling. No new UI selector. Review exact desired resume policy and reset disclosure.
- Retry/Again explicitly refused for capability-gated or persisted Hermes server conversations, including response loss; explanation appears as turn problem without clearing prior answer/actions. Generic providers still submit retries normally.
- No remote publish; workflow preview version overrides untouched.

## Finalization approval and evidence policy

- Spec review `deleg_d5c36f7b`: PASS. Quality review `deleg_0ea2e794`: APPROVED, no blockers; covers canceled-header ownership fixes above.
- Earlier uncommitted/review-pending statements and APK hashes describe historical checkpoints, not final delivery.
- Finalization commits only source, tests, fallback version and these plan/verification documents. Rebuild established-key release from that exact commit; no push/PR or server changes.
- Final post-commit tests, APK manifest/signature, source-input manifest, APK/ZIP hashes and build logs are recorded in external scratch delivery metadata, avoiding a self-referential commit hash.
- Lint remains unpassed after documented OOM; release build excludes lintVital tasks. Physical-device and live Hermes acceptance remain unverified.
