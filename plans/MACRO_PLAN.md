# MACRO_PLAN — ktc_mangle.h + ktc_types.h integration

## What was added

Two new runtime headers alongside `ktc_core.h`:

- `src/main/resources/ktc/ktc_mangle.h` — naming macros, variadic generic dispatch, optional access helpers
- `src/main/resources/ktc/ktc_types.h`  — struct-definition macros (`KTC_DEFINE_OPT`, `KTC_DEFINE_ARRAY`, `KTC_DEFINE_OPT_ARRAY`)

Both are already included at the top of `ktc_core.h`.

---

## Naming conventions (final)

| Kotlin type | C name |
|-------------|--------|
| `T?` | `T$Opt` |
| `Array<T, N>` | `ktc_Array_T_N` |
| `Array<T, N>?` | `ktc_Array$Opt$_T_N` |
| `Foo<A>` | `Foo$1_A` |
| `Foo<A, B>` | `Foo$2_A_B` |
| `Foo<A>?` | `Foo$Opt$1_A` |
| `Foo<A, B>?` | `Foo$Opt$2_A_B` |

---

## Macro reference

### Naming / type construction

```c
KTC_OPT_TYPE(T)                  // T$Opt
KTC_ARRAY_TYPE(T, N)             // ktc_Array_T_N
KTC_OPT_ARRAY_TYPE(T, N)         // ktc_Array$Opt$_T_N
KTC_GENERIC_TYPE(BASE, ...)      // BASE$N_A1_..._AN  (N = arg count, variadic)
KTC_OPT_GENERIC_TYPE(BASE, ...)  // BASE$Opt$N_A1_..._AN
KTC_FUNCTION_NAME(T, NAME)       // T_NAME
```

### Struct definitions (in generated headers)

```c
KTC_DEFINE_OPT(T)                // typedef struct T$Opt { ktc_OptionalTag tag; T value; } T$Opt
KTC_DEFINE_ARRAY(T, N)           // typedef struct ktc_Array_T_N { T arr[N]; } ktc_Array_T_N
KTC_DEFINE_OPT_ARRAY(T, N)       // typedef struct ktc_Array$Opt$_T_N { ktc_OptionalTag tag; ktc_Array_T_N value; } ...
```

### Optional value helpers (in generated .c code)

```c
KTC_SOME(T, v)    // (T$Opt){ .tag = ktc_SOME, .value = (v) }
KTC_NONE(T)       // (T$Opt){ .tag = ktc_NONE }
KTC_IS_SOME(v)    // (v).tag == ktc_SOME
KTC_IS_NONE(v)    // (v).tag == ktc_NONE
KTC_UNWRAP(v)     // (v).value
```

### Nesting works (preprocessor expansion fix already applied)

```c
// This correctly expands to testpackage_Configuration$3_...:
KTC_GENERIC_TYPE(
    testpackage_Configuration,
    KTC_OPT_TYPE(KTC_GENERIC_TYPE(testpackage_Initial, testpackage_Param)),
    KTC_OPT_TYPE(KTC_GENERIC_TYPE(
        AdvancedSelector,
        KTC_OPT_TYPE(testpackage_Param),
        KTC_GENERIC_TYPE(testpackage_UpdatedParam, KTC_OPT_TYPE(testpackage_JsonHolder))
    )),
    KTC_GENERIC_TYPE(testpackage_Finalizer, testpackage_Param, KTC_OPT_TYPE(testpackage_Error))
)
```

The `_IMPL_` indirection in `KTC_OPT_TYPE` / `KTC_ARRAY_TYPE` / `KTC_OPT_ARRAY_TYPE` ensures
the argument is fully expanded before `##` concatenation — without it, passing another macro
call as argument would paste `)` with `$Opt`, producing undefined behaviour.

---

## Codegen changes remaining

### Step 1 — ktc_core.h migration (in progress)
- [x] Add `#include "ktc_mangle.h"` and `#include "ktc_types.h"`
- [ ] Replace `KTC_OPTIONAL(T)` callsites → `KTC_DEFINE_OPT(T)` (requires `$Opt` rename below)
- [ ] Remove superseded macros: `KTC_OPTIONAL`, `KTC_OPTIONAL_GENERIC_NAME`, `KTC_OPTIONAL_GENERIC`
- [ ] Add `extern const ktc_core_AnyVt ktc_core_Boxed<X>_vt;` declarations

### Step 2 — `$Opt` rename in codegen strings
All places in Kotlin codegen emitting `$Optional` suffix → `$Opt`:
- `CCodeGen.kt` — `optCTypeName`, `optNone`, `optSome`
- `CCodeGenEmit.kt` — `KTC_OPTIONAL_GENERIC` emit, forward-decl strings

Update all expected-output `.h`/`.c` files in `tests/`.

### Step 3 — Optional access macros in generated code
- `optNone()` → `KTC_NONE(innerType)`
- `optSome()` → `KTC_SOME(innerType, expr)`
- null checks → `KTC_IS_NONE(v)` / `KTC_IS_SOME(v)`
- value access → `KTC_UNWRAP(v)`

### Step 4 — Generic `$N_` arity markers
Find name builder in `CCodeGenScan.kt` / `CCodeGenEmit.kt`:
```kotlin
// current: "${baseName}_${typeArgs.joinToString("_")}"
// new:     "${baseName}\$${typeArgs.size}_${typeArgs.joinToString("_")}"
```
Optional-generic: `Base$Opt$N_A1_..._AN` (was `Base$Optional_TypeArg`).
Mechanical test-output update pass required.

### Step 5 — Fixed-array struct types
- `Arr.toCType()` in `CoreTypes.kt`: `sized != null -> "ktc_Array_${elem.toCType()}_${sized}"`
- `cTypeStr(KtcType.Arr)` in `CCodeGenCTypes.kt`: same pattern
- Track `Set<Pair<String,Int>>` of used (T,N) pairs; emit `KTC_DEFINE_ARRAY(T,N)` in generated headers
- Struct fields: `T field[N]` → `ktc_Array_T_N field` (drop `$len` companion)
- Constructor init: `memcpy(x.field.arr, expr, N*sizeof(T))`
- Return type: remove `void + $out` pattern; return `ktc_Array_T_N` directly
- Element access: `name[i]` → `name.arr[i]` in `CCodeGenExpr.kt`
- New: `KtcType.Nullable(Arr(elem, sized=N))` → `ktc_Array$Opt$_T_N`; emit `KTC_DEFINE_OPT_ARRAY`

### Step 6 — BoxedPrimitive vtables
Add per-primitive `ktc_core_AnyVt` impls to `ktc_core.c` for Byte, Short, Int, Long,
Float, Double, Bool, Char, UByte, UShort, UInt, ULong, String.

Update boxing in `CCodeGenStmts.kt` (lines 282, 348, 969):
```c
// current: ktc_Any x = {{2}, (void*)&tVal};
// new:     ktc_Any x = {{2}, (void*)&tVal, &ktc_core_BoxedInt_vt};
```
Add `getBoxedVtName(typeName): String?` in `CCodeGen.kt`.

---

## Execution order

1. ktc_core.h include (done) → then `$Opt` rename → test output pass
2. Optional access macros (cosmetic, low risk)
3. Generic `$N_` naming + test output pass
4. Fixed-array structs (most invasive)
5. BoxedPrimitive vtables (self-contained)
