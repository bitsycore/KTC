# PENDING_REFACTOR.md — KtcType Migration (String → Typed)

## Status: ~97% — Dispatch logic complete

The KtcType migration is **effectively complete** for all codegen dispatch logic. 
~220+ string checks eliminated from ~250 original. All 32 integration + 541 unit tests pass.

## Remaining: 12 bridge/internal items

These are the boundary layer between string-based and KtcType-based code — 
internal resolution functions that convert between the two representations.
They are NOT dispatch logic and staying string-based is correct.

| File | Count | Items |
|------|-------|-------|
| CCodeGen.kt | 4 | `pointerClassName` — string-bridge helper. `isValueNullableType` — string-bridge helper. |
| CCodeGenCTypes.kt | 4 | `parseResolvedTypeName` internals — string→KtcType resolution. `printfFmt` string version. |
| CCodeGenInfer.kt | 4 | `inferMethodReturnType` string manipulation — type name derivation from array elements. |
| CCodeGenStmts.kt | 2 | `currentFnReturnType == "Any"` — string-based return type tracking (needs `currentFnReturnKtcType` field). |

## Future work

- Replace `currentFnReturnType: String` with `currentFnReturnKtcType: KtcType` in CCodeGen.kt
- Replace `pointerClassName` call sites with direct KtcType pattern matching (7 call sites remain)
- Replace `isValueNullableType` call sites with `isValueNullableKtc` (remaining ones use string types)
- Rewrite `inferMethodReturnType` / `inferDotType` to produce KtcType natively (currently returns strings)
