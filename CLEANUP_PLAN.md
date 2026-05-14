# CLEANUP_PLAN.md — Deduplication & Stronger Typing

## Status: Not started. ~580 lines target.

All 32 integration + 541 unit tests must pass at each phase.

---

## Phase 1: Object/Companion Fusion (~100 lines)

**Goal:** Eliminate 5 pairs of near-identical object-vs-companion blocks.

**Helper:**
```kotlin
fun CCodeGen.resolveDotObjInfo(dot: DotExpr): ObjInfo? = when {
    dot.obj is NameExpr && objects.containsKey(dot.obj.name) -> objects[dot.obj.name]
    dot.obj is NameExpr && classCompanions.containsKey(dot.obj.name) ->
        objects[classCompanions[dot.obj.name]!!]
    else -> null
}
```

**Sites:**
- [ ] genDot (2062-2075) — object/companion field access → 1 block
- [ ] inferDotTypeKtc (488-499) — object/companion prop type → 1 block
- [ ] emitAssign (773-785) — object/companion prop write → 1 block
- [ ] genMethodCall (1802-1850) — object/companion method call → 1 block
- [ ] genDot (2058-2061) — enum entry → also unified into resolveDotObjCName

---

## Phase 2: Array/RawArray Unification (~50 lines)

**Goal:** Single `genHeapAllocBody(elemName, sizeExpr, withLen)` helper.

**Sites:**
- [ ] genBuiltinCall HeapAlloc (750-771) — typeArgs branch
- [ ] genBuiltinCall HeapAlloc (796-811) — targetType branch
- [ ] genBuiltinCall HeapArrayZero (837-869) — two branches
- [ ] resolveTypeNameStr (181-182, 263-264) — remove duplicate RawArray check

---

## Phase 3: Null-Guard Fusion (~40 lines)

**Goal:** Shared `nullGuardExpr()` helper for Ptr/Nullable/value decision tree.

**Sites:**
- [ ] emitAssign safe dot guard (710-719)
- [ ] emitExprStmt safe method guard (1123-1132)
- [ ] genSafeMethodCall guard (2003-2009)
- [ ] genSafeDot guard (2150-2163)

---

## Phase 4: Save/Restore FunContext (~200 lines)

**Goal:** `FunContext` data class with save/restore for 5 emit functions.

**Sites:**
- [ ] emitMethod (561-615)
- [ ] emitExtensionFun (663-731)
- [ ] emitGenericFunInstantiations (795-837)
- [ ] emitStarExtFunInstantiations (887-920)
- [ ] emitStarExtFunForGenericInterface (972-997)

---

## Phase 5: hashCode Extraction (~80 lines)

**Goal:** `emitImplicitHashCode()` helper called from emitClass + emitGenericClass.

---

## Phase 6: Constructor Extraction (~80 lines)

**Goal:** `emitPrimaryConstructorBody()` helper.

---

## Phase 7: Stronger Typing (~30 lines)

**Goal:** Convert remaining string-accepting helpers to KtcType:
- [ ] cTypeStr(String) → cTypeStr(KtcType)
- [ ] typeFlatName(String) → typeFlatName(KtcType.User)
- [ ] isBuiltinType(String) → isBuiltinType(KtcType)
- [ ] hasNullableReceiverExt(String) → hasNullableReceiverExt(KtcType)
