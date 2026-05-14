# PENDING_REFACTOR.md — KtcType Migration (String → Typed)

## Status

Currently: **~78% migrated**.  ~186 string checks eliminated.
Total remaining: 70 across all codegen files.

## Completed

- `KtcType.Any` first-class type (was `User(BuiltinTypeDef("Any"))`)
- `genBin` comparison/equality path: `endsWith("?")`, `endsWith("*")`, `isValueNullableType`, `pointerClassName`, `== "String"` → KtcType
- `genMethodCall`: `recvType == "String"` (~20 sites), `isArrayType`, hashCode `when(rt)` → KtcType
- `genDot` + `genSafeDot`: `endsWith("?")`, `endsWith("*")`, `isArrayType`, `pointerClassName`, `anyIndirectClassName`, `== "String"` → KtcType (~15 sites)
- `genSbAppend` → `genSbAppendKtc(KtcType)`: fully rewritten with KtcType dispatch + Ptr data class fix
- `emitDataClassToString` → uses `genSbAppendKtc` with native KtcType
- `genWhenCond` → KtcType pattern matching
- `emitAssign` → safe-dot assignment, value-nullable, `anyIndirectClassName`, `isAnyPtrNullVar`, `isArrayType` → KtcType
- `emitVarDecl` → `isPointer`, `isValueNullable`, `isFuncType`, `isArrayType`, `const` qualifiers → KtcType
- `emitPrintStmtInner` → nullable detection, data class checks, `anyIndirectClassName`, class/object/interface → KtcType
- `genIsAs` IsCheckExpr → `endsWith("*")`, `endsWith("?")`, `isArrayType`, `isValueNullableType` → KtcType
- `expandCallArgs` → pointer/array param checks → KtcType
- `genNotNull` → 3 string checks → KtcType
- `narrowSubjectForBranch` → `endsWith("*")` → KtcType
- `hashFieldExpr` → `hashFieldExprKtc(KtcType)` with pattern matching
- `printfFmtKtc(KtcType)` / `isValueNullableKtc(KtcType)` helpers added
- `genPrintCall` / `genSbAppendKtc` → fixed `$has` for pointer-nullable, data class Ptr detection

## Remaining by file

| File | Count | Main patterns |
|------|-------|---------------|
| CCodeGenCTypes.kt | 10 | `cTypeStr(string)`, `isArrayType`, `printfFmt` string version, `arrayElementCType` |
| CCodeGenInfer.kt | 21 | `endsWith("Array")`, `endsWith("*")`, return-type string manipulation |
| CCodeGenExpr.kt | 14 | genBin remnants, genToString string version, `endsWith("*")` in dispose/star-projection |
| CCodeGenStmts.kt | 12 | emitReturn `== "Any"`, netNullable `$has`, emitVarDecl `isArrayType(t)` |
| CCodeGen.kt | 8 | `pointerClassName`, `anyIndirectClassName`, `isBuiltinType`, `isValueNullableType` |
| CCodeGenEmit.kt | 3 | `retResolved` string comparisons |
| ArraysMapping.kt | 2 | internal array mapping string checks |

## Known risks

- **KtcType.Ptr** wraps both user pointers (`Vec2*`) and typed arrays (`Ptr<Arr<Int>>` = `IntArray`). When migrating `endsWith("*")`, must exclude array-typed pointers via `inner !is KtcType.Arr`.
- **`inferExprType` → `String?`** vs **`inferExprTypeKtc` → `KtcType?`**: The Ktc version is a wrapper around the string version. Real migration means native KtcType inference.
- **`$has`** is legacy and should be fully removed. Value types use `tag == ktc_SOME`, pointer types use `!= NULL`.
