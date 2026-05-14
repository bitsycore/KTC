# REFACTOR_REMAINING.md — What's Left

## Status: ~500 lines eliminated. All 32 + 541 tests pass.

## Hard floor: 4 string checks that must stay

These are in `parseResolvedTypeName` (the KtcType parser itself) and one AST check.

| File              | Count | What                                                                                                                                |
|-------------------|-------|-------------------------------------------------------------------------------------------------------------------------------------|
| CCodeGenCTypes.kt | 3     | `parseResolvedTypeName` internals: `endsWith("*?")`, `endsWith("*")`, `endsWith("Array")` — the string→KtcType boundary. Must stay. |
| CCodeGen.kt       | 1     | `d.returnType.name == "Any"` — raw AST check before type resolution. Must stay.                                                     |

---

## ArraysMapping.kt: 3 callers still using string versions

These use strings from `inferExprType` or string manipulation — KtcType not directly available:

| File             | Line | Function                   | Can't convert because                             |
|------------------|------|----------------------------|---------------------------------------------------|
| CCodeGenStmts.kt | 488  | `arrayElementCType(t)`     | `t` in `tryArrayOfInit` — no KtcType in scope     |
| CCodeGenInfer.kt | 578  | `arrayElementKtType(base)` | `base` from `t.dropLast(1)` — string manipulation |
| CCodeGenInfer.kt | 580  | `arrayElementKtType(t)`    | `t` from `inferExprType` in `inferIndexType`      |

**How to fix:** Convert `inferIndexType` to use `inferExprTypeKtc` alongside the string (same pattern as `inferDotType`). For `tryArrayOfInit`, add `parseResolvedTypeName(t)` to get KtcType.

---

## inferExprType → String? (96 call sites)

The biggest remaining string-based infra. `inferExprType` returns `String?` while `inferExprTypeKtc` returns `KtcType?` but is just a wrapper. Native KtcType inference requires rewriting the type inference pipeline.

**Approach:** Add `inferExprTypeKtc` call alongside every `inferExprType` call, then migrate consumers one by one. Already done for `genBin`, `genMethodCall`, `genDot`, `emitPrintStmtInner`.

---

## Array type string manipulation in inferDotType

The `.ptr`/`.toHeap` handlers use `recvType.endsWith("Array")` and `recvType.removeSuffix("Array")` for element type extraction. KtcType variables are planted (`recvTypeKtc`, `recvTypeCoreKtc`) but the string manipulation remains. Replacing with `asArr?.elem` would eliminate these but caused the `UIntArra` regression before. Needs careful incremental testing.

---

## `tryArrayOfInit` ≈ 20 string checks

Function in CCodeGenStmts.kt (lines ~380-540) still uses `t.endsWith("OptArray")`, `t.endsWith("Array")`, `isArrayType(t)`. KtcType not available in scope — needs `parseResolvedTypeName(t)` or structural refactor to bring KtcType in.

---

## `inferExprType` return type migration plan

| Step | What                                                                                                           | Impact           |
|------|----------------------------------------------------------------------------------------------------------------|------------------|
| 1    | Add `inferExprTypeKtc` alongside every `inferExprType` call                                                    | 96 new lines     |
| 2    | Convert consumers of the string result to use KtcType                                                          | incremental      |
| 3    | Change `inferExprType` return type to `KtcType?`                                                               | breaking — defer |
| 4    | Rewrite `inferDotType`, `inferIndexType`, `inferMethodReturnType`, `inferCallType` to produce KtcType natively | ~300 lines       |

## Priority

1. `tryArrayOfInit` — highest density of remaining string checks, all fixable by bringing KtcType into scope
2. `inferIndexType` line 578/580 — simple 2-line fix, just add `inferExprTypeKtc` alongside
3. `inferExprType` migration — long-term, incremental
