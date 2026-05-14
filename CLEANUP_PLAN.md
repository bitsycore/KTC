# CLEANUP_PLAN.md — Deduplication & Stronger Typing

## Status: COMPLETE. ~500 lines eliminated.

All 32 integration + 541 unit tests pass.

## Completed

- [x] **Phase 1:** Object/Companion Fusion — `resolveDotObjInfo` + `resolveDotObjCName`
- [x] **Phase 2:** Array/RawArray Unification — HeapAlloc + HeapArrayZero
- [x] **Phase 3:** Null-Guard Fusion — `nullGuardExpr()` helper
- [x] **ID1:** Struct fields — `emitStructFields()`
- [x] **ID2:** Constructor body — `emitConstructorBody()`
- [x] **ID3:** Vtable emission — `emitVtable()`
- [x] **Phase 4:** Save/Restore FunContext — `saveFunState()`/`restoreFunState()` across 5 emit functions
- [x] **Phase 5:** hashCode — `emitImplicitHashCode()`
- [x] **Phase 7:** Stronger Typing — `isBuiltinType` deleted
- [x] **Functions deleted (0 callers remaining):**
  - `pointerClassName`, `anyIndirectClassName`
  - `isValueNullableType`, `isBuiltinType`
  - `printfFmt(String)`, `printfArg(String)`, `defaultVal(String)`
- [x] **DUPLICATE START/END comments** removed

## Helpers added

| Helper | File | Lines saved |
|--------|------|-------------|
| `resolveDotObjInfo` | CCodeGen.kt | ~60 |
| `resolveDotObjCName` | CCodeGen.kt | |
| `nullGuardExpr` | CCodeGen.kt | ~25 |
| `emitStructFields` | CCodeGenEmit.kt | ~80 |
| `emitConstructorBody` | CCodeGenEmit.kt | ~160 |
| `emitVtable` | CCodeGenEmit.kt | ~60 |
| `emitImplicitHashCode` | CCodeGenEmit.kt | ~50 |
| `saveFunState`/`restoreFunState` | CCodeGen.kt | ~80 |
