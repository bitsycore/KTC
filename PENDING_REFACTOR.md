# PENDING_REFACTOR.md — KtcType Migration (String → Typed)

## Status: ~99% — Steps 1-3 done. 8 bridge items remain.

Steps completed:
- [x] Step 1: `isValueNullableType` → `isValueNullableKtc` (3 call sites in CCodeGenEmit.kt)
- [x] Step 2: `currentFnReturnType` → `currentFnReturnKtcType` (8 writes + 12 reads across 4 files)
- [x] Step 3: `pointerClassName`/`anyIndirectClassName` → KtcType where possible
  - 3 of 4 direct `pointerClassName` calls eliminated (the 4th is a fallback for KtcType edge cases)
  - 4 of 10 `anyIndirectClassName` calls eliminated
  - Remaining 7 are in functions without KtcType available (need Step 4)
- [ ] Step 4: `inferMethodReturnType` / `inferDotType` → KtcType natively (192 lines, structural change)

8 remaining are all bridge/internal:
| File | Count | Items |
|------|-------|-------|
| CCodeGen.kt | 4 | `pointerClassName`/`isValueNullableType` definitions — string→KtcType bridges |
| CCodeGenCTypes.kt | 4 | `parseResolvedTypeName` internals — KtcType parser |
| CCodeGenInfer.kt | 4 | `inferDotType` / `inferMethodReturnType` string manipulation |

~220+ string checks eliminated from ~250 original. All tests pass.

## Remaining: 12 bridge/internal items → 0

These are the boundary layer. All targetable for elimination.

---

## Execution Plan (ordered by difficulty)

### Step 1: `isValueNullableType` → `isValueNullableKtc`
**Lines:** 3 | **Risk:** Zero | **Files:** CCodeGenEmit.kt

All 3 call sites do `isValueNullableType("${vStr}?")` where `vStr = vKtc.toInternalStr`.
KtcType is in scope. Replace with `isValueNullableKtc(KtcType.Nullable(vKtc))`.

| Line | Function | Variable |
|------|----------|----------|
| 588  | `emitMethod` | `vKtcParam` |
| 700  | `emitExtensionMethod` | `vKtcExtParam` |
| 1704 | `emitGenericMethod` | `vKtcFunParam` |

---

### Step 2: `currentFnReturnType` → `currentFnReturnKtcType`
**Lines:** ~25 | **Risk:** Low | **Files:** CCodeGen.kt, CCodeGenEmit.kt, CCodeGenStmts.kt

Add `internal var currentFnReturnKtcType: KtcType? = null` alongside the String field.
At all 8 write sites the KtcType (`vRetKtc`/`vRetKtcFun`/etc.) is in scope — store it.
Save/restore alongside the string save/restore at function entry/exit.
Convert `currentFnReturnType == "Any"` / `currentFnReturnBaseType() == "Any"` readers.

| Site | File | Line | KtcType available |
|------|------|------|-------------------|
| Write | CCodeGenEmit.kt | 578 | `vRetKtc` |
| Write | CCodeGenEmit.kt | 806 | `vRetKtcGen` |
| Write | CCodeGenEmit.kt | 1217 | `vRetKtcM` |
| Write | CCodeGenEmit.kt | 1679 | `vRetKtcFun` |
| Read  | CCodeGenStmts.kt | 938 | `currentFnReturnBaseType() == "Any"` |
| Read  | CCodeGenStmts.kt | 1035 | `currentFnReturnType == "Any"` |

---

### Step 3: `pointerClassName`/`anyIndirectClassName` → KtcType pattern matching
**Lines:** ~14 call sites | **Risk:** Medium | **Files:** CCodeGenExpr.kt, CCodeGenInfer.kt, CCodeGenStmts.kt

At each call site, introduce `inferExprTypeKtc()` to get KtcType, then pattern match:
`(ktcCore as? KtcType.Ptr)?.inner?.let { it as? KtcType.User }?.baseName`

| Type | File | Line | Source |
|------|------|------|--------|
| Direct | CCodeGenExpr.kt | 502 | `pointerClassName(lt)` — fallback |
| Direct | CCodeGenExpr.kt | 1642 | `pointerClassName(recvType)` |
| Direct | CCodeGenExpr.kt | 1690 | `pointerClassName(recvType)` |
| Direct | CCodeGenInfer.kt | 414 | `pointerClassName(recvType)` |
| Indirect | CCodeGenExpr.kt | 547-548 | `anyIndirectClassName` |
| Indirect | CCodeGenExpr.kt | 1919,1925 | `anyIndirectClassName` |
| Indirect | CCodeGenExpr.kt | 2619 | `anyIndirectClassName` |
| Indirect | CCodeGenExpr.kt | 3283 | `anyIndirectClassName` |
| Indirect | CCodeGenInfer.kt | 527, 559 | `anyIndirectClassName` |
| Indirect | CCodeGenStmts.kt | 1837,1906 | `anyIndirectClassName` |

After this, `pointerClassName` and `anyIndirectClassName` can be deprecated.

---

### Step 4a: `inferDotType` → returns KtcType natively
**Lines:** ~59 | **Risk:** High | **File:** CCodeGenInfer.kt

Change return type `String?` → `KtcType?`. Internally replace `recvType` string with `recvKtc` from `inferExprTypeKtc`. Replace `endsWith("Array")`, `endsWith("*")`, `removeSuffix` with KtcType field access. Update 3 call sites.

### Step 4b: `inferMethodReturnType` → returns KtcType natively
**Lines:** ~133 | **Risk:** High | **File:** CCodeGenInfer.kt

Same approach. More complex due to array element type extraction via string manipulation.
