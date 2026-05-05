# Change: Interface Type — Stack-Return via Tagged Union

## Problem
When a function returns an interface type (e.g. `fun foo(): Shape`), the current code heap-allocates the concrete class instance.

## Solution
Change the interface fat-pointer struct from `{ void* obj; vtable* vt }` to a tagged union containing all known implementing classes.

## PROGRESS (resume from here)

### Completed

1. **`interfaceImplementors` reverse map** — added at line ~106 in CCodeGen.kt:
   ```kotlin
   private val interfaceImplementors = mutableMapOf<String, MutableList<String>>()
   ```
   Built initially from `classInterfaces` before vtable loop (line ~472), updated incrementally by `emitTransitiveInterfaceVtables`.

2. **Split `emitInterface` → `emitInterfaceVtable` + `emitIfaceInfo`** — `emitInterfaceVtable` emits only TYPE_ID + vtable struct. `emitIfaceInfo` emits the tagged-union struct (with `.data.` union for 2+ implementors, plain field for 1, `void* obj` fallback for 0). Added forward declarations for ALL interfaces after class struct emission.

3. **Restructured emission order in `emit()`**:
   - Phase 1: Interface vtables (early, line ~384)
   - Phase 1b: Forward-declare all interface structs (line ~410)
   - Phase 2: Class structs (unchanged)
   - Phase 3: Monomorphized generic class vtables
   - After vtables: Build `interfaceImplementors`, emit tagged-union structs (line ~494-512)
   - Phase 4: Functions (unchanged)

4. **`_as_` functions updated** — `ifaceAsInit()` helper generates correct designated initializer:
   - 0 impls: `(Iface){(void*)$self, &vt}`
   - 1 impl: `(Iface){.ClassName = *$self, .vt = &vt}`
   - 2+ impls: `(Iface){.data.ClassName = *$self, .vt = &vt}`
   
   Both `emitInterfaceVtablesForClass` and `emitTransitiveInterfaceVtables` use this helper.
   `emitTransitiveInterfaceVtables` also updates `interfaceImplementors` incrementally.

### TODO (in order)

#### 5. Update all interface dispatch sites: `recv.obj` → `(void*)&recv`

All 10 dispatch locations need `$xxx.obj` → `(void*)&$xxx`:

| Line | Code to change | Context |
|------|---------------|---------|
| 3264 | `$recv.vt->set($recv.obj, $idx, $value)` | Indexed assignment on interface |
| 3994 | `$arrExpr.vt->iterator($arrExpr.obj)` | for-in iterator dispatch |
| 4001 | `${iterVar}.vt->hasNext(${iterVar}.obj)` | while loop hasNext |
| 4003 | `${iterVar}.vt->next(${iterVar}.obj)` | while loop next |
| 4176 | `$recv.vt->get($recv.obj, $idx)` | Indexed access (nullable ret, pre-stmt) |
| 4181 | `$recv.vt->get($recv.obj, $idx)` | Indexed access (non-null ret) |
| 4369 | `$recv.vt->${containsMethod.name}($recv.obj, $elem)` | in-check on interface |
| 5038 | `"$recv.obj"` / `"$recv.obj, $argStr"` | Interface method call (line 5038) |
| 5047 | `$recv.vt->$method($allArgs)` | Uses allArgs from 5038 |
| 5338 | `$recv.vt->${e.name}($recv.obj)` | Interface property access via vtable |

**Key**: Since `(void*)&recv == (void*)&recv.data` (first member), and the struct field / union data is always at offset 0, `(void*)&$recv` works for all cases (single field, union, or void* fallback).

For line 5038: change from `"$recv.obj"` to `"(void*)&$recv"` and from `"$recv.obj, $argStr"` to `"(void*)&$recv, $argStr"`.

#### 6. Fix return statements (line 3455-3469) — no malloc

Current code:
```kotlin
if (retIface.isNotEmpty() && interfaces.containsKey(retIface)
    && exprType != null && classes.containsKey(exprType)
    && classInterfaces[exprType]?.contains(retIface) == true) {
    val t = tmp()
    val cExprType = pfx(exprType)
    impl.appendLine("$ind${cExprType}* $t = ${tMalloc("sizeof($cExprType)")};")
    impl.appendLine("$ind*$t = $expr;")
    impl.appendLine("${ind}return ${cExprType}_as_$retIface($t);")
}
```

Replace with stack-allocated direct assignment using the interface struct:
```kotlin
if (retIface.isNotEmpty() && interfaces.containsKey(retIface)
    && exprType != null && classes.containsKey(exprType)
    && classInterfaces[exprType]?.contains(retIface) == true) {
    val cExprType = pfx(exprType)
    val cIface = pfx(retIface)
    val impls = interfaceImplementors[retIface] ?: emptyList()
    val fieldPath = when {
        impls.size <= 1 -> ".$exprType"
        else -> ".data.$exprType"
    }
    val t = tmp()
    impl.appendLine("$ind${cIface} $t;")
    impl.appendLine("$ind$t$fieldPath = $expr;")
    impl.appendLine("$ind$t.vt = &${cExprType}_${retIface}_vt;")
    impl.appendLine("${ind}return $t;")
}
```

Alternatively, use compound literal (cleaner):
```c
return (InterfaceTest_Shape){.data.Circle = Circle_create(1.0f), .vt = &Circle_Shape_vt};
```
But may be problematic if `$expr` is a statement (not an expression). Use the 3-line temp approach above.

#### 7. Fix variable initialization for interface types (line ~2949-2957)

Current code (line ~2878-2888):
```kotlin
if (interfaces.containsKey(t)) {
    val initType = inferExprType(s.init)
    if (initType != null && classes.containsKey(initType) && classInterfaces[initType]?.contains(t) == true) {
        val backing = tmp()
        val expr = genExpr(s.init)
        flushPreStmts(ind)
        impl.appendLine("$ind${pfx(initType)} $backing = $expr;")
        impl.appendLine("$ind$ct ${s.name} = ${pfx(initType)}_as_$t(&$backing);")
        return
    }
}
```

This should still work as-is because `_as_` now copies into the union. The backing var + _as_ call stays the same. No change needed UNLESS there are issues with the _as_ function returning a larger struct. But the C calling convention handles this fine.

#### 8. Run tests

```bash
.\gradlew test     # Unit tests
.\run_tests.ps1    # All integration tests  
```

### Possible issues to watch for

- **C compound literal syntax**: `(Type){.field = val}` requires C99/C11 mode. Our targets (GCC, Clang, MSVC) support this.
- **Union member names**: The class name in `emitIfaceInfo` uses raw `className` (not prefixed). The `ifaceAsInit` uses the same. Must match.
- **Forward declarations**: All interface structs are forward-declared after class emission (line ~410). Verify no "incomplete type" errors.
- **Alignment**: Union may be larger than any single member (max of all sizes). This is expected and acceptable.
- **Cross-package interfaces**: Interfaces from other packages may not have complete implementor lists. The tagged union falls back to `void* obj` for 0 known implementors. Ensure cross-package interface dispatch still works.
- **Generic interfaces (`List_Int`, `MutableList_Int`)**: Need to verify monomorphized interface tagged unions include generic class implementors.
