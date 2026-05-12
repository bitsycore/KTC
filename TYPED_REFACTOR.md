# Typed Refactor Plan

Replace ad-hoc string-based type representation with structured `KtcType` classes.

## Phase 1: Define KtcType hierarchy

**File:** `src/main/kotlin/CoreTypes.kt`

```kotlin
sealed class KtcType {
    data class Prim(val kind: PrimKind) : KtcType()
    enum class PrimKind { Byte, Short, Int, Long, UByte, UShort, UInt, ULong, Float, Double, Boolean, Char, Rune }
    object Str : KtcType()
    object Void : KtcType()
    data class User(val name: String, val typeArgs: List<KtcType>, val isData: Boolean, val isEnum: Boolean) : KtcType()
    data class Arr(val elem: KtcType, val ptr: Boolean, val sized: Int?) : KtcType()
    data class Ptr(val inner: KtcType) : KtcType()
    data class Nullable(val inner: KtcType) : KtcType()
    data class Func(val params: List<KtcType>, val ret: KtcType) : KtcType()
    data class OptArray(val elem: KtcType) : KtcType()
}
```

## Phase 2: Migrate leaf functions

- [ ] `cTypeStr(String)` → `KtcType.toCType()`
- [ ] `isArrayType(String)` → `KtcType.isArray`
- [ ] `isValueNullableType(String)` → `KtcType.isValueNullable`
- [ ] `arrayElementCType(String)` → `Arr.elem.toCType()`
- [ ] `toStringPrimitiveMaxLen` → `Prim.maxLen`

## Phase 3: Migrate name resolution

- [ ] `resolveTypeName(TypeRef)` returns KtcType
- [ ] `pfx(name)` integrated into KtcType.name resolution

## Progress

### Phase 1: CoreType.kt ✅
- `Prim`, `Str`, `Void`, `User`, `Arr`, `Ptr`, `Nullable`, `Func`, `OptArray` all defined
- `from(TypeRef)` builder ✅
- `.toCType()`, `.isArray`, `.isPointer`, `.isNullable`, `.elementType` ✅
- All 32 integration + unit tests pass ✅

### Phase 2: First consumer (in progress)
- [ ] Add `TypeRef.toKtcType()` bridge in CCodeGenCTypes
- [ ] Migrate `cTypeStr` to use KtcType  
- [ ] Verify tests
