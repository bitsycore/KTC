# PENDING_REFACTOR.md — KtcType Migration (String → Typed)

## Status: ~99.5% complete. 4 legitimate string checks remain.

All 32 integration + 541 unit tests pass.

## Deleted functions (0 callers)
- `pointerClassName` — KtcType pattern: `(ktc as? Ptr)?.inner?.let { it as? User }?.baseName`
- `anyIndirectClassName` — alias to above
- `isValueNullableType` — replaced by `isValueNullableKtc`
- `printfFmt(String)` — replaced by `printfFmt(KtcType)`
- `printfArg(String)` — replaced by `printfArg(KtcType)`
- `defaultVal(String)` — replaced by `defaultVal(KtcType)`

## Converted functions (no more string `t`)
- `emitPrintStmtInner` — fully KtcType-based. `t` still declared for backward compat.
- `genPrintCall` — fully KtcType-based
- `genPrintfFromTemplate` — fully KtcType-based

## Remaining: 4 hard-floor items

| File | Count | Items |
|------|-------|-------|
| CCodeGenCTypes.kt | 3 | `parseResolvedTypeName` internals — **the KtcType parser itself**. Must parse strings. |
| CCodeGen.kt | 1 | `d.returnType.name == "Any"` — checks AST before resolution. |

## Key lessons
- `KtcType.Ptr` wraps user pointers AND typed arrays (Ptr<Arr<T>>). Always exclude `inner !is Arr`.
- `inferExprTypeKtc` can return null — always provide `?: KtcType.Prim(Int)` fallback.
- `parseResolvedTypeName` is the string→KtcType boundary. Its string checks are legitimate.
