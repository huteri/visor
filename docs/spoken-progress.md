# Spoken progress (experimental, phone-generated)

## Enable and verify

1. Use a parent-reviewed, normally signed candidate. Debug artifact is `app/build/outputs/apk/debug/app-debug.apk`, package `com.macedonga.visor` (not a separate `.debug` package). Do not uninstall an existing installation to bypass signature mismatch: that deletes local settings/history. Parent handles release signing.
2. Open Settings → Meta AI → Answers → **Spoken progress (experimental)** → **On**. Default is Off; upgrades retain existing preferences. This does not change the saved glasses/phone voice choice.
3. Choose answer wait 55 seconds for the first long trial. Ensure a working phone TTS engine and the desired language are installed. Keep glasses connected. Do not run Live AI/session mode for this trial.
4. Ask a real ordinary question whose backend needs over 40 seconds. The same real model request continues. If unfinished, phone TTS says “Still working on your answer.” at roughly 40 seconds, followed by encoded silence. Final uses existing saved Voice choice: **Glasses voice** ends/drains phone stream and tries native TTS on the same interaction (experimental); **Phone voice** keeps final in the same phone-encoded stream. No new setting. Existing `glassesVoice=false` remains phone final; saved preferences are never changed. No fabricated task status or extra progress API request is used. The same fixed phrase repeats at anchored intervals derived from the same effective Meta AI **Wait for answer** snapshot as the first update: `min(40 seconds, effective wait - 10 seconds)`, where effective wait is the lower of the configured wait and the glasses-reported processing timeout when known. With a 55-second effective wait, updates remain at 40/80/120/160/200/240/280 seconds; with 30 seconds, updates occur at 20/40/60 seconds and continue every 20 seconds while unfinished. Repeat cadence has a one-second minimum for unusually short reported waits; first-update timing remains unchanged. Each update uses separate single-flight synthesis, bounded to 15 seconds; silence continues in the same stream/encoder during preparation. Missed updates are discarded, never queued or spoken late. Ready final cancels pending progress before handoff.
5. Fast answer: ask a short question. If ready before progress, expect the saved ordinary voice and no progress speech. Switch progress Off and repeat; baseline native framing/deadline remains selected as before.
6. Cancellation: during a long turn, start a new interaction, change Assistant settings, disconnect glasses, or let glasses remotely close the interaction. Expect no subsequently admitted old audio/final. Bytes already accepted by transport or firmware cannot be recalled. Repeat a fresh question after each test.
7. Exclusions: enable progress then ask in Live AI and session/use case 7. These remain on their ordinary existing answer path, not a silent return. Verify answering and microphone follow-up physically.
8. For a 20-second answer wait, progress target is 10 seconds (question-start elapsed counted). At 55 seconds, target is 40 seconds. TTS preparation starts up to 15 seconds ahead and runs beside model work. Slow/failing TTS or absent owned stream ID logs a specific unavailable message and falls back to normal final before opening a progress stream where possible.

## What this does not prove

- No physical device was connected during local verification (`adb devices` empty). Audible progress, real platform Opus continuity, final beyond firmware watchdog, and follow-up microphone closure need physical acceptance. Firmware may close early despite audio. App cannot promise extending its interaction deadline.
- No app diagnostic arm/UI or special voice command was added. A normal model answer saying it waited 70 seconds is NOT a deterministic test. JVM controller test uses a fake clock/model with actual dynamic completion at 70 seconds; this is software evidence, not hardware evidence.
- English progress phrase and Italian translation implemented; other locales fall back to English. Phone voice availability remains engine-dependent.
- One stream, one encoder, zero PCM while waiting. Phone-final text split in ordered 240-character segments; each synthesized clip limited to 20 seconds / 4 MB WAV before reading. Exception rather than truncation if clip too long. Whole-turn limit 300 seconds includes question setup, model, synthesis and draining. Very long final answers can hit this limit; visible problem/log records bounded failure.
- Progress preparation limited to 15 seconds. Before stream starts, synthesis/codec/ID failure keeps real final where possible. After opening, stream/synthesis/transport failure terminates with explicit problem; no second overlapping fallback stream.
- Opt-in request uses 300-second network read allowance, bounded by controller's remaining whole-turn limit. Ordinary network read timeout remains 45 seconds; SSE parser/request behavior retained. Existing model tool rounds and compatibility retries are unchanged, not extra progress requests.
- Host tests use fake codec/sink, not Android MediaCodec instrumentation. Real Assistant adapter tests replace Android IO and exercise native framing/ownership; source-level wiring guards supplement controller tests. WAV boundary/codec device instrumentation remains review/test follow-up.

## Native-final experiment

- After model completes, no more progress/silence generation. Flush EOS packets, pace/drain them, then send stream end `6000` and timing `389`. Immediately reuse ordinary `speakOnGlasses` framing on same interaction: preamble, `53/60` in existing mode-dependent order, then `392`. No guessed `61/64`, new interaction ID, backend changes, duplicate fallback, or phone synthesis of native-final text.
- Every termination/native write validates captured turn ownership; remote close/cancellation wins even between writes. Whole-turn bound remains 300 seconds.
- Logs: `Spoken progress: native handoff begin (experimental)` and `Spoken progress: native completion sent (not playback confirmation)`. Sent means transport call returned, not audible playback or hardware acceptance.
- Physical trial: select Glasses voice at Settings → Meta AI → Answers, enable Spoken progress, ask genuinely slow question. Listen for phone progress then native final. Repeat with Phone voice to compare existing stream path. Test fast answer, Off, remote close, next question/follow-up. Hardware remains unverified without connected glasses.

## Local verification

Base `ceed9aa`, branch `feat/spoken-progress`; working changes uncommitted. No push, signing-secret read, server/auth/private-capture change.

Environment:

```
JAVA_HOME=/home/huteri/.local/jdk/jdk-21.0.10+7
ANDROID_HOME=/home/huteri/Android/Sdk
flock /home/huteri/.hermes/cache/scratch/visor-gradle.lock ./gradlew :core:assistant:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=2
```

Logs under `/home/huteri/.hermes/cache/scratch/phone-progress/`:

- `red-policy.log` → `green-policy.log`: missing opt-in/config/policy API, then pass.
- `red-controller.log` → `green-controller.log`: missing dynamic single-stream controller API, then pass.
- `red-wiring.log` → `green-wiring.log`: missing Android route/persistence assertions, then pass + debug assemble.
- `red-fallback-ui.log` → `green-fallback-ui.log`: missing owned-ID fallback API/UI, then pass + assemble.
- `red-safe-final.log` → `green-safe-final.log`: missing guarded normal-final callback, then cancellation test pass + assemble.
- `red-generation-lifecycle.log` → `green-generation-full.log`: ownership-loss exception type and absent generation/channel guards failed; fixed and full suite passed. Cleanup assertion adjusted to account for coroutine exception stack-recovery wrapper while checking original cause/suppressed cleanup.
- `red-no-late-progress.log` → `green-no-late-progress.log`: ready final during paced progress exposed late-packet send; fixed by readiness recheck after pacing delay.
- `red-bounded-preparation.log` → `green-bounded-preparation-full.log`: missing preparation bound/owned keepalive guard; fixed.
- `red-network-budget.log` → `green-network-final.log`: missing opt-in network-context allowance; fixed. Final full required tasks exit 0.

Some RED logs are expected missing-feature compile failures; behavioral race/generation regressions also captured. Additional lifecycle/boundary tests added as passing regression coverage, not all independently demonstrated failing first.

Native-final logs under `/home/huteri/.hermes/cache/scratch/phone-progress-native-final/`: `red-controller-behavior.log` (2 expected behavioral failures), `red-adapter.log` (4 expected missing-routing failures), `red-disclosure.log` (only disclosure failure; native tests green). Final suite/assemble evidence in `green-full-assemble.log`: `:core:assistant:testDebugUnitTest :app:assembleDebug` exit 0, 63 tests, 0 failures/errors. `verification.json` records debug APK hash/size. `git diff --check` passed. `adb devices` empty; physical native playback not tested. Existing redundant-cast compiler warning and JVM sharing warning remain.

## Setting-derived repeat cadence

Cadence-only revision on `588389d`, branch `feat/spoken-progress`; no version bump, commit, push, Live AI, voice or five-minute limit changes. Existing effective-wait calculation in `Assistant.kt` remains unchanged. Logs under `/home/huteri/.hermes/cache/scratch/visor-progress-cadence/`: `red.log` demonstrates missing 20/40/60-second updates with a 30-second wait; `green.log` passes that regression. `red-boundary.log` records missing `repeatMs` policy API; boundary policy now preserves zero first-delay semantics while flooring repeats at one second. Additional controller regressions cover 20-second supported waits and the 11-second boundary.

`full.log` exposed two old synthesis-count assumptions: shorter cadence prepares another progress clip before the 11-second final. Tests now distinguish progress clips from final segments, retaining EOS/native-final and full ordered-final assertions. `full-retry.log`: `:core:assistant:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=2`, serialized with the documented lock/JDK/SDK, exit 0. Fresh JUnit totals: assistant 75, app 10, all 85 passed (existing 81 plus four new tests), zero failures/errors/skips. `git diff --check` passed. Hardware playback remains unverified.

## Repeating progress / five-minute candidate

Logs under `/home/huteri/.hermes/cache/scratch/phone-progress-repeat/`: `repeat-red.log` → `repeat-green.log` proves missing 80-second speech then anchored 40/80 speech before final at 90; `budget-red.log` → `budget-green.log` proves 300-second controller/network allowance and final at 290 with seven updates; `initial-late-red.log` proves late initial synthesis, fixed to cancel/fall back rather than speak late. Full gate first exposed pacing regression and stale disclosure assertion (`green-full-assemble.log`); `pacing-debug.log` isolated packet-boundary gap. Preserve codec pacing when discarding pending packet, update disclosure assertion. Final `green-full-assemble-retry.log`: `:core:assistant:testDebugUnitTest :app:assembleDebug`, exit 0, 69 tests, zero failures/errors. Includes prior ownership/EOS/native-final tests, pending-repeat cancellation at final 70, late ownership revocation at 230, and skipped slow repeat with no backlog. `git diff --check` passes. Debug APK SHA-256 `2d14223710f646572e1680920445f6edc4788ad92301b7bbbc9d073c5014bc7a`, 78,669,014 bytes; not signed release delivery. Parent review/version 10304 packaging remains separate. No commit, push, signing-secret access, backend change or new physical acceptance claimed.

Controller coverage: single model/encoder/open/end, dynamically appended 70-second final, paced silence, 20/55-second budget, elapsed setup/synthesis, ready-final synthesis cancellation, revocation/model cancellation, whole-turn deadline, fractional cumulative pacing, long scheduler stall, original exception plus cleanup failure, segmented ordered final, no progress packet admitted after ready final, normal-final cancellation. Physical playback and firmware survival are separate open gates.
