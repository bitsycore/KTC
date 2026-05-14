# PENDING_REFACTOR.md — KtcType Migration (String → Typed)

## Status

The KtcType hierarchy (`src/main/kotlin/types/CoreTypes.kt`) is well-designed but
used as a thin bridge layer.  The core codegen still operates mostly on raw strings.
This file tracks remaining work to complete the migration.

Currently: **~25% migrated**.  ~50 string checks eliminated so far.

## Completed (this session)

- Added `printfFmtKtc(KtcType)` in `CCodeGenCTypes.kt`
- Added `isValueNullableKtc(KtcType)` in `CCodeGen.kt`
- `genBin` comparison/equality path: `endsWith("?")`, `endsWith("*")`, `isValueNullableType`, `pointerClassName`, `== "String"` → KtcType pattern matching
- `genMethodCall`: `recvType == "String"` (~20 sites), `isArrayType`, hashCode `when(rt)` → KtcType
- `genDot` + `genSafeDot`: `endsWith("?")`, `endsWith("*")`, `isArrayType`, `pointerClassName`, `anyIndirectClassName`, `== "String"` → KtcType (~15 sites)

## Patterns to eliminate (string → KtcType)

| String check                    | KtcType equivalent                        | Remaining count |
|---------------------------------|-------------------------------------------|-----------------|
| `endsWith("*")`                 | `is KtcType.Ptr`                          | ~45             |
| `endsWith("?")`                 | `is KtcType.Nullable`                     | ~55             |
| `isArrayType(str)`              | `ktc.isArrayLike`                         | ~35 call sites  |
| `isValueNullableType(str)`      | `isValueNullableKtc(ktc)`                 | ~30 call sites  |
| `pointerClassName(str)`         | extract `(ktc as? Ptr)?.inner` + class    | ~5 call sites   |
| `anyIndirectClassName(str)`     | same as above                             | ~20 call sites  |
| `printfFmt(str)`                | `printfFmtKtc(ktc)`                       | 5 call sites    |
| `inferExprType()` → `String?`   | `inferExprTypeKtc()` → `KtcType?`         | 96 call sites   |
| `resolveTypeName().toInternalStr` | use KtcType directly                    | 54 sites        |

## Priority work areas

### 1. `genDot` — `CCodeGenExpr.kt` (~lines 2000-2200)

Heavily string-based field-access dispatch. Uses `anyIndirectClassName(str)`,
`endsWith("*")`, `endsWith("?")`, `isArrayType`.  Already has `recvTypeKtc`
available at call sites — just not used for dispatch.

### 2. `genSbAppend` — `CCodeGenExpr.kt` (~lines 2940-3000)

Entire signature is string-based: `genSbAppend(sb: String, expr: String, type: String)`.
Should take `KtcType` for the `type` parameter.

### 3. `inferDotType` / `inferExprType` — `CCodeGenInfer.kt`

Returns `String?` from 96 call sites vs `KtcType?` from only 7. The Ktc
version currently just wraps the string result via `parseResolvedTypeName`.
Real migration means rewriting the inference to produce KtcType natively.

### 4. `CCodeGenStmts.kt` — statement codegen

Many `endsWith("*")`, `endsWith("?")`, `isArrayType`, `isValueNullableType` checks
in variable declarations, assignments, for-loops, when-expressions.

### 5. `CCodeGenEmit.kt` — class emission

Uses `.isArrayLike`, `.asArr` natively in some places (~15 sites) — this is good.
But `genSbAppend` dispatch is still string-based.  `hashFieldExpr` takes String.

### 6. `printfFmt` call sites (5 call sites)

All pass raw `String` from `inferExprType`. After upstream `inferExprType` is
migrated, these can switch to `printfFmtKtc`.

## Known risks when migrating

- **Array types (`IntOptArray?`, etc.)**: `parseResolvedTypeName` classifies these
  as `Nullable(Ptr(Arr(...)))`, which is semantically different from the string
  check `endsWith("*?")`.  String checks on null-comparison paths for trampolined
  array params are sensitive — keep string fallback during migration.
- **`inferExprType` return type**: Changing from `String?` to `KtcType?` is a
  massive breaking change. Start by introducing a parallel `inferExprTypeKtc`
  path, migrate callers incrementally, then deprecate the string version.
- **Builtin types (Pair, Triple, Any)**: These are handled correctly by KtcType
  via `BuiltinTypeDef`. No known issues.

## Test strategy

After each batch of changes, run:
```
.\run_tests.ps1           # all 32 integration tests
./gradlew test             # all 541 unit tests
```

Both must pass with zero failures before proceeding to the next batch.
