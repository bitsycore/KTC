# CLEANUP_PLAN.md — Deduplication & Stronger Typing

## Status: Phases 1-5 complete. ~150 lines eliminated.

All 32 integration + 541 unit tests pass.

## Completed

- [x] **Phase 1: Object/Companion Fusion** — 4 sites via `resolveDotObjInfo` + `resolveDotObjCName`
- [x] **Phase 2: Array/RawArray Unification** — HeapAlloc + HeapArrayZero unified
- [x] **Phase 3: Null-Guard Fusion** — `nullGuardExpr()` helper, 3 of 4 sites
- [x] **Phase 5: hashCode Extraction** — `emitImplicitHashCode()` helper
- [x] **Phase 6: Constructor Extraction** — `emitConstructorBody()` helper (ID2)
- [x] **Phase 6b: Struct field emission** — `emitStructFields()` helper (ID1)
- [x] **Phase 6c: Vtable emission** — `emitVtable()` helper (ID3)
- [x] **Phase 7: Stronger Typing** — `isBuiltinType` + `builtinTypes` deleted
- [x] **Functions deleted:** `pointerClassName`, `anyIndirectClassName`, `isValueNullableType`, `isBuiltinType`, `printfFmt(String)`, `printfArg(String)`, `defaultVal(String)`

Total: **~400 lines eliminated** across all phases.

## Remaining

- [ ] **Phase 4: Save/Restore FunContext** (~200 lines) — 5 functions with 70% identical save/restore boilerplate. Needs Context data class.
- [ ] **Phase 6: Constructor Extraction** (~80 lines) — emitClass/emitGenericClass constructor bodies share `heapAllocTargetType` side effects. Complex extraction.
- [x] **Phase 7: Stronger Typing** — `isBuiltinType` deleted (4 callers → KtcType), `builtinTypes` set removed.
