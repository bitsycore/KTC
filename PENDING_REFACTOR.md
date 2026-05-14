# PENDING_REFACTOR.md — KtcType Migration (String → Typed)

## Status: ~99.5% complete. 5 legitimate string checks remain.

All dispatch logic uses KtcType. `pointerClassName`, `anyIndirectClassName`,
and `isValueNullableType` DELETED (0 callers). All 32 tests pass.

## Remaining: 5 string checks — all in the KtcType parser + 1 AST check

| File | Count | Items |
|------|-------|-------|
| CCodeGenCTypes.kt | 4 | `parseResolvedTypeName` internals: `endsWith("*?")`, `endsWith("*")`, `endsWith("?")`, `endsWith("Array")`. The **KtcType parser itself** — must parse strings. Cannot be eliminated. |
| CCodeGen.kt | 1 | `d.returnType.name == "Any"` — checks AST `TypeRef.name` before resolution, not a resolved type. Legitimate source-code check. |

## Deleted functions (0 callers)

- `pointerClassName` — string bridge, replaced by KtcType pattern: `(ktc as? Ptr)?.inner?.let { it as? User }?.baseName`
- `anyIndirectClassName` — alias, same replacement
- `isValueNullableType` — string bridge, replaced by `isValueNullableKtc`

## Key lessons

- `KtcType.Ptr` wraps user pointers AND typed arrays (Ptr<Arr<T>> = IntArray). Always exclude `inner !is KtcType.Arr` when migrating `endsWith("*")`.
- `parseResolvedTypeName` is the string→KtcType boundary. It MUST use string checks. Don't try to eliminate these.
