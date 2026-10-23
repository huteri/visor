# Context continuity fix

Base: latest fetched origin/main 113cbd5b17449d2cb5df815efe9c41be84a78bc8. Isolated branch fix/hermes-context-continuity. Existing worktrees preserved.

1. Add stable Hermes session support scoped to logical diary conversation, endpoint and credential. Discover from echoed server header; bootstrap history once, then new turns only. Capture JSON/SSE headers before reading; fail explicitly on expired IDs, never automatically retry POST.
2. Honor configured conversation memory cutoff, deterministic Live AI recent resume versus explicit new session; isolate auxiliary calls.
3. Bound prior visual reference retention and respect image/privacy deletion rules. Add production-seam regressions using RED/GREEN slices.
4. Run constrained unit tests and release build if signing inputs available. Record exact commands, results and remaining device gates. No push/PR.
