# REFACTOR_REMAINING.md — What's Left

## Status: ~600 lines eliminated. All 33 integration + 570 unit tests pass.

## Hard floor: 4 string checks that must stay

These are in `parseResolvedTypeName` (the KtcType parser itself) and one AST check.

| File              | Count | What                                                                                                                                |
|-------------------|-------|-------------------------------------------------------------------------------------------------------------------------------------|
| CCodeGenCTypes.kt | 3     | `parseResolvedTypeName` internals: `endsWith("*?")`, `endsWith("*")`, `endsWith("Array")` — the string→KtcType boundary. Must stay. |
| CCodeGen.kt       | 1     | `d.returnType.name == "Any"` — raw AST check before type resolution. Must stay.                                                     |

---

## ✅ ArraysMapping.kt: all string callers converted

| File                 | What                                         | Resolution                                                                    |
|----------------------|----------------------------------------------|-------------------------------------------------------------------------------|
| ~~CCodeGenStmts.kt~~ | ~~`arrayElementCType(t)` in tryArrayOfInit~~ | Replaced with `arrayElementCTypeKtc(tKtcCore)` via `parseResolvedTypeName(t)` |
| ~~CCodeGenInfer.kt~~ | ~~`arrayElementKtType` in inferIndexType~~   | Replaced with `arrayElementKtTypeKtc(tKtcCore)` via `inferExprTypeKtc`        |
| ~~CCodeGenInfer.kt~~ | ~~`t.dropLast(1)` string manipulation~~      | Replaced with `tKtcCore.inner.toInternalStr`                                  |

**String functions `arrayElementCType()` and `arrayElementKtType()` removed — ~90 lines eliminated.**

---

## ✅ tryArrayOfInit string checks converted

| What                             | Resolution                                                            |
|----------------------------------|-----------------------------------------------------------------------|
| ~~`recvType.endsWith("Array")`~~ | Replaced with `inferExprTypeKtc(dot.obj)?.isArrayLike`                |
| ~~`t.endsWith("OptArray")`~~     | Replaced with KtcType structural check via `parseResolvedTypeName(t)` |

---

## ✅ inferIndexType Ptr branch converted

| What                             | Resolution                                              |
|----------------------------------|---------------------------------------------------------|
| ~~`t.dropLast(1)` string manip~~ | Replaced with `tKtcCore.inner.toInternalStr`            |
| ~~`isArrayType(base)`~~          | No longer needed (pre-checked: `inner !is KtcType.Arr`) |

---

## inferExprType → String? (96 call sites)

The biggest remaining string-based infra. `inferExprType` returns `String?` while `inferExprTypeKtc` returns `KtcType?` but is just a wrapper. Native KtcType inference requires rewriting the type inference pipeline.

**Approach:** Add `inferExprTypeKtc` call alongside every `inferExprType` call, then migrate consumers one by one. Already done for `genBin`, `genMethodCall`, `genDot`, `emitPrintStmtInner`.

---

## Array type string manipulation in inferDotType

The `.ptr`/`.toHeap` handlers use `recvType.endsWith("Array")` and `recvType.removeSuffix("Array")` for element type extraction. KtcType variables are planted (`recvTypeKtc`, `recvTypeCoreKtc`) but the string manipulation remains. Replacing with `asArr?.elem` would eliminate these but caused the `UIntArra` regression before. Needs careful incremental testing.

---

## `inferExprType` return type migration plan

| Step | What                                                                                                           | Impact           |
|------|----------------------------------------------------------------------------------------------------------------|------------------|
| 1    | Add `inferExprTypeKtc` alongside every `inferExprType` call                                                    | 96 new lines     |
| 2    | Convert consumers of the string result to use KtcType                                                          | incremental      |
| 3    | Change `inferExprType` return type to `KtcType?`                                                               | breaking — defer |
| 4    | Rewrite `inferDotType`, `inferIndexType`, `inferMethodReturnType`, `inferCallType` to produce KtcType natively | ~300 lines       |

## Priority

1. `inferExprType` migration — long-term, incremental
2. `inferDotType` array string manipulation — needs careful testing
