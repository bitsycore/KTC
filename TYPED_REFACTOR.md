# Typed Refactor Plan
Replace ad-hoc string-based type representation with structured `KtcType` classes.
End state: **zero internal string-based type handling** — only AST→KtcType and
KtcType→C-string edges remain.

## Architecture Principle
Ast.kt (pure syntax)
ClassDecl, PropDecl, CtorParam, FunDecl, TypeRef — no resolution deps
↓ parsed by Parser
CoreTypes.kt (type system)
KtcType hierarchy (Prim, Str, Void, User, Arr, Ptr, Nullable, Func)
TypeDef interface — describes a resolved class/object/enum/interface
PropertyDef — describes a resolved property (unifies ctor + body props)
↓ implemented by
CCodeGenStructures.kt (resolved descriptors)
ClassInfo : TypeDef, ObjInfo : TypeDef, EnumInfo : TypeDef, IfaceInfo : TypeDef
BuiltinTypeDef : TypeDef (for Pair/Triple/Any/StrBuf intrinsics)
↓ used by
CCodeGen*.kt (code generation)
resolveTypeName(TypeRef) → KtcType
cTypeStr(KtcType) → C string
`KtcType.User` holds a **reference** to a `TypeDef` — it does NOT duplicate
`baseName`, `pkg`, or `kind`. Those come from `decl: TypeDef`.
---
## Current Architecture (Phases 1–2 done)
AST TypeRef → resolveTypeName() → internal type string → cTypeStr(String) → C type
↑                      ↑
classesname, interfacesname  symbolPrefixname
(6 string-keyed maps)            pfx(name)
**Bridge** (CCodeGenCTypes.kt): `stringToKtc(name)` converts string → KtcType via
a `user()` helper that consults `symbolPrefix` to derive `User.pkg`. `cTypeStr(KtcType)`
converts back. `cType(TypeRef)` routes through `typeToKtc` → `cTypeStr(KtcType)`.
---
## Target Architecture
AST TypeRef → resolveTypeName() → KtcType → cTypeStr(KtcType) → C type
↑
ClassInfo.flatName (carries pkg, kind — no symbolPrefix)
User.decl: TypeDef (holds methods, properties, super-types)
---
## Phase 1: Define KtcType hierarchy ✅ (done)
**File:** `src/main/kotlin/CoreTypes.kt`
- `Prim(kind: PrimKind)`, `Str`, `Void`, `User`, `Arr`, `Ptr`, `Nullable`, `Func`
  all defined as sealed subclasses.
- `.toCType()` on every type.
- Queries: `.isArray`, `.isPointer`, `.isSizedArray`, `.isPrimitive`, `.isString`,
  `.isVoid`, `.elementType`, `.nullable`, `.internalName`.
- `from(TypeRef)` builder in companion object.
- `User` currently carries `baseName`, `typeArgs`, `kind`, `pkg` with `flatName`
  getter — these will move to `TypeDef` in Phase 3.
---
## Phase 2: Bridge & User cleanup ✅ (done)
**Files modified:** `CoreTypes.kt`, `CCodeGenCTypes.kt`
- `Arr.ptr` flag removed (replaced by `Ptr(Arr(...))` wrapper).
- `OptArray` type removed (replaced by `Ptr(Arr(Nullable(elem)))`).
- `User` refactored: `baseName`, `typeArgs`, `kind` (UserKind), `pkg`, `flatName`.
- `stringToKtc` uses `user()` helper for `User` creation with auto-pkg derivation
  from `symbolPrefix`.
- `cTypeStr(KtcType.User)` uses `flatName` instead of `pfx()`.
- `cType(TypeRef)` routes through `typeToKtc` → `cTypeStr(KtcType)` (passes
  `@Size` annotations).
- All 32 integration tests pass.
---
## Phase 3: TypeDef + PropertyDef Infrastructure
**Goal:** Every declaration descriptor carries its own identity (pkg, kind)
and properties use a proper descriptor type instead of `Pair<String, TypeRef>`.
`KtcType.User` wraps a `TypeDef` reference.
---
### 3.0 — Introduce `PropertyDef`
**File:** `CCodeGenStructures.kt`
Create a proper property descriptor to replace `Pair<String, TypeRef>` and the
separate visibility tracking sets (`privateProps`, `valCtorProps`, `privateSetProps`).
```kotlin
data class PropertyDef(
    val name: String,
    val typeRef: TypeRef,              // → KtcType once type resolution is typed (Phase 4)
    val isVal: Boolean,                // true = val, false = var
    val isPrivate: Boolean = false,
    val isPrivateSet: Boolean = false,
    val isOverride: Boolean = false,
    val isConstructorParam: Boolean = false,
    val initExpr: Expr? = null
)
Created from CtorParam + PropDecl during collectDecls().
Replaces Pair<String, TypeRef> in ClassInfo.ctorProps / ctorPlainParams
and in ObjInfo.props. Eliminates the separate privateProps, valCtorProps,
privateSetProps sets — these become computed from the properties list.
Refactor ClassInfo:
data class ClassInfo(
    val name: String,
    val isData: Boolean,
    val properties: List<PropertyDef> = emptyList(),       // unified: ctor + body props
    val ctorPlainParams: List<PropertyDef> = emptyList(),  // non-val/var ctor params
    val methods: MutableList<FunDecl> = mutableListOf(),
    val initBlocks: List<Block> = emptyList(),
    val typeParams: List<String> = emptyList(),
) {
    // Computed helpers (replace deleted sets):
    val ctorProps get() = properties.filter { it.isConstructorParam }
    val bodyProps get() = properties.filter { !it.isConstructorParam }
    val privateProps get() = properties.filter { it.isPrivate }.map { it.name }.toSet()
    val valCtorProps get() = properties.filter { it.isConstructorParam && it.isVal }.map { it.name }.toSet()
    val privateSetProps get() = properties.filter { it.isPrivateSet }.map { it.name }.toSet()
    val isGeneric get() = typeParams.isNotEmpty()
    fun isValProp(name: String): Boolean =
        name in valCtorProps || bodyProps.any { it.name == name && it.isVal }
}
Refactor ObjInfo:
data class ObjInfo(
    val name: String,
    val properties: List<PropertyDef> = emptyList(),
    val methods: MutableList<FunDecl> = mutableListOf(),
) {
    val privateProps get() = properties.filter { it.isPrivate }.map { it.name }.toSet()
}
CollectDecls changes: Replace CtorParam.isVal/isVar/isPrivate extraction and
PropDecl mapping with unified PropertyDef creation.
Verify: ./gradlew jar succeeds, 32 integration tests pass.
---
3.1 — Introduce TypeDef interface
File: CoreTypes.kt
interface TypeDef {
    val baseName: String
    val pkg: String
    val kind: KtcType.UserKind
    val flatName: String get() = "$pkg$baseName"
    val methods: List<FunDecl>
    val properties: List<PropertyDef>
    val typeParams: List<String>
    val superTypeDefs: List<TypeDef>        // resolved super-interfaces
}
Note: FunDecl is from Ast.kt. CoreTypes.kt already depends on AST types
(TypeRef, IntLit) so this dependency is acceptable.
For intrinsic types (Pair, Triple, Any, StrBuf) that don't have a ClassDecl:
internal data class BuiltinTypeDef(
    override val baseName: String,
    override val pkg: String = "ktc_",
    override val kind: KtcType.UserKind = KtcType.UserKind.Class
) : TypeDef {
    override val methods get() = emptyList<FunDecl>()
    override val properties get() = emptyList<PropertyDef>()
    override val typeParams get() = emptyList<String>()
    override val superTypeDefs get() = emptyList<TypeDef>()
}
Verify: ./gradlew jar succeeds (interface defined, no implementors yet).
---
3.2 — Make declaration types implement TypeDef
File: CCodeGenStructures.kt
data class ClassInfo(...) : TypeDef {
    override val baseName get() = name
    override var pkg: String = ""          // set during collectDecls
    override val kind get() = if (isData) UserKind.DataClass else UserKind.Class
    // methods, properties, typeParams are already fields
    override val superTypeDefs get() = emptyList()  // populated later
}
data class ObjInfo(...) : TypeDef {
    override val baseName get() = name
    override var pkg: String = ""
    override val kind get() = UserKind.Object
    override val typeParams get() = emptyList()
    override val superTypeDefs get() = emptyList()
}
data class EnumInfo(...) : TypeDef {
    override val baseName get() = name
    override var pkg: String = ""
    override val kind get() = UserKind.Enum
    override val methods get() = emptyList()
    override val properties get() = emptyList()
    override val typeParams get() = emptyList()
    override val superTypeDefs get() = emptyList()
}
data class IfaceInfo(...) : TypeDef {
    override val baseName get() = name
    override var pkg: String = ""
    override val kind get() = UserKind.Interface
    // methods, properties, typeParams are already fields
    override val superTypeDefs get() = emptyList()  // populated later
}
Verify: ./gradlew jar succeeds.
---
3.3 — Populate pkg on each TypeDef during collectDecls()
File: CCodeGen.kt, collectDecls() (~lines 931–988)
When ClassInfo/ObjInfo/EnumInfo/IfaceInfo are created:
- allFiles pass (line 931–961): info.pkg = fpfx (file's package-derived prefix)
- current file pass (line 970–988): info.pkg = this.prefix
- nested classes (lines 963–967): nestedInfo.pkg = parentClassInfo.pkg
- generic materialization (CCodeGenScan.kt lines 272, 327): materialized.pkg = template.pkg
At this point, every TypeDef knows its own flat C name — info.flatName resolves
without symbolPrefix or pfx().
symbolPrefix is still populated for non-TypeDef symbols (top-level funs, top-level
props, enum value arrays) that don't have a TypeDef.
Verify: ./gradlew jar succeeds, 32 integration tests pass.
---
3.4 — Change KtcType.User to wrap TypeDef
File: CoreTypes.kt
Before:
enum class UserKind { Class, DataClass, Object, Interface, Enum }
data class User(
    val baseName: String,
    val typeArgs: List<KtcType> = emptyList(),
    val kind: UserKind = UserKind.Class,
    val pkg: String = ""
) : KtcType() {
    val flatName get() = "$pkg$baseName"
    override fun toCType(): String = flatName
}
After:
enum class UserKind { Class, DataClass, Object, Interface, Enum }
data class User(
    val decl: TypeDef,
    val typeArgs: List<KtcType> = emptyList()
) : KtcType() {
    val baseName get() = decl.baseName
    val kind get() = decl.kind
    override fun toCType(): String = decl.flatName
}
baseName, kind, pkg are removed from User — always accessed via decl.
flatName is on TypeDef. toCType() returns decl.flatName.
Verify: ./gradlew jar succeeds.
---
3.5 — Update all User creation sites
File: CCodeGenCTypes.kt — stringToKtc() + user() helper (lines 586–667)
Remove the user() helper. Instead:
- For types in classes, objects, enums, interfaces: create
  KtcType.User(classInfo) directly (the info IS a TypeDef).
- For Pair/Triple intrinsic: create BuiltinTypeDef(baseName = "Pair_Int_String",
  pkg = "ktc_") then KtcType.User(builtinTypeDef).
- For ktc_StrBuf and Any: BuiltinTypeDef + KtcType.User(builtinTypeDef).
- Fallback unknown types: BuiltinTypeDef(baseName = resolved).
File: CoreTypes.kt — from(TypeRef) companion function
Remove the companion (or simplify to accept (String) -> TypeDef? resolver).
All type resolution goes through resolveTypeName(TypeRef): KtcType on
CCodeGen — unify on one entry point.
File: CCodeGenCTypes.kt — cTypeStr(KtcType.User) (lines 670–692)
Simplify. Since User.toCType() = decl.flatName, cTypeStr for User is:
is KtcType.User -> when {
    ktc.baseName == "Any" -> "ktc_Any"
    ktc.baseName == "ktc_StrBuf" -> "ktc_StrBuf"
    ktc.baseName.startsWith("Pair_") -> "ktc_${ktc.baseName}"
    ktc.baseName.startsWith("Triple_") -> "ktc_${ktc.baseName}"
    else -> ktc.toCType()   // = decl.flatName
}
Verify: ./gradlew jar succeeds, 32 integration tests pass.
---
3.6 — Replace pfx(name) with info.flatName in emit code
Files: CCodeGenEmit.kt, CCodeGenExpr.kt, CCodeGenStmts.kt
Where a ClassInfo / ObjInfo / EnumInfo / IfaceInfo is already in scope
(after a classes[name] or similar lookup), replace pfx(info.name) with
info.flatName.
Pattern to replace (~60% of all pfx() calls):
// Before:
val cName = pfx(ci.name)
// After:
val cName = ci.flatName
This eliminates pfx() calls in:
- Class struct typedef emission
- Method function emission  
- Interface vtable struct / wrapping function emission
- Enum typedef / values array emission
- Iterator dispatch in for-in loops
- Constructor emission
Non-migrated pfx() calls remaining after this step:
- Top-level functions (no TypeDef — tracked separately)
- Top-level properties (no TypeDef — tracked separately)
- ::funRef function references
- genCall for bare function calls resolving to pfx(name)
- Ad-hoc name construction (pfx("${base}_${suffix}"))
- Various helpers that construct names from strings
Verify: ./gradlew jar succeeds, 32 integration tests pass.
---
Phase 4: Typed Type Resolution
Goal: resolveTypeName(TypeRef) returns KtcType instead of String.
All internal type tracking (scopes, inference) uses KtcType.
---
4.1 — Add resolveTypeNameKtc(TypeRef): KtcType
File: CCodeGenCTypes.kt
Create alongside existing resolveTypeName(TypeRef): String:
internal fun CCodeGen.resolveTypeNameKtc(t: TypeRef?): KtcType {
    if (t == null) return KtcType.Prim(KtcType.PrimKind.Int)
    // ... find TypeDef by name in classes/interfaces/enums/objects maps
    // ... handle built-in types
    // ... wrap in KtcType.User(typeDef)
}
Keep old resolveTypeName intact — all existing callers still use it.
---
4.2 — Add TypeRef.resolveKtc() convenience
fun TypeRef.resolveKtc(gen: CCodeGen): KtcType = gen.resolveTypeNameKtc(this)
---
4.3 — Change scopes to store KtcType
File: CCodeGen.kt (lines 192–199)
// Before:
internal val scopes = ArrayDeque<MutableMap<String, String>>()
internal fun defineVar(name: String, type: String) { scopes.last()[name] = type }
internal fun lookupVar(name: String): String? { ... }
// After:
internal val scopes = ArrayDeque<MutableMap<String, KtcType>>()
internal fun defineVar(name: String, type: KtcType) { scopes.last()[name] = type }
internal fun lookupVar(name: String): KtcType? { ... }
All defineVar call sites now pass KtcType. Most callers already have a string
that can go through stringToKtc or resolveTypeNameKtc.
preScanVarTypes also switches to Map<String, KtcType>.
---
4.4 — Migrate inferExprType to return KtcType
File: CCodeGenInfer.kt (~460 lines)
Change return type from String? to KtcType?. This function is called ~100+
times across the codebase.
Strategy:
1. Add inferExprTypeKtc(): KtcType? alongside inferExprType(): String?.
2. Migrate callers one by one: genMethodCall → genDot → genCall →
   genExpr helpers → genStmt helpers.
3. Each migrated caller uses KtcType internally, converting .toCType() for
   any remaining string-based consumers.
4. After all callers migrated, remove inferExprType() and rename the Ktc variant.
---
4.5 — Migrate inferDotType, inferMethodReturnType
Files: CCodeGenInfer.kt
All type inference helpers return KtcType instead of String.
---
4.6 — Migrate findOverload to compare KtcType
File: CCodeGen.kt (~line 1180)
Currently matches argument types by string comparison. Change to KtcType
equality/compatibility checks using .internalName or direct comparison.
---
4.7 — Remove old resolveTypeName(String) and rename
Once all callers use the KtcType version, remove string-based resolveTypeName
and rename resolveTypeNameKtc → resolveTypeName.
At this point, resolveTypeName is the only function that internally calls
stringToKtc (for the AST→KtcType conversion at the input boundary).
Verify: ./gradlew jar succeeds, 32 integration tests pass.
---
Phase 5: Typed Dispatch
Goal: Method/field/constructor dispatch uses KtcType instead of string-based
lookups like classes[typeString].
---
5.1 — Add KtcType-based lookup helpers
File: CCodeGen.kt
internal fun classInfoFor(type: KtcType): ClassInfo? =
    (type as? KtcType.User)?.decl as? ClassInfo
internal fun ifaceInfoFor(type: KtcType): IfaceInfo? =
    (type as? KtcType.User)?.decl as? IfaceInfo
internal fun objInfoFor(type: KtcType): ObjInfo? =
    (type as? KtcType.User)?.decl as? ObjInfo
internal fun enumInfoFor(type: KtcType): EnumInfo? =
    (type as? KtcType.User)?.decl as? EnumInfo
Pattern change:
// Before:
if (classes.containsKey(recvTypeStr)) {
    val ci = classes[recvTypeStr]!!
    val method = ci.methods.find { it.name == name }
}
// After:
val ci = classInfoFor(recvType)
val method = ci?.methods?.find { it.name == name }
---
### 5.2 — Migrate `genMethodCall` dispatch
**File:** `CCodeGenExpr.kt` (~line 1248, ~400 lines)
The central method dispatch function. Currently:
1. `inferExprType(dot.obj) → String`
2. `classes.containsKey(recvType)`, `interfaces.containsKey(recvType)`, etc.
After migration:
1. `inferExprType(dot.obj) → KtcType?`
2. `classInfoFor(recvType)`, `ifaceInfoFor(recvType)`, etc.
3. C name emission uses `decl.flatName` instead of `pfx()`
Built-in methods (String.length, hashCode, array methods, pointer methods) are
already handled with KtcType checks (`recvType is KtcType.Str`, etc.) — minimal
change needed.
---
5.3 — Migrate genDot (field access)
File: CCodeGenExpr.kt (~line 1774, ~80 lines)
Same pattern. Field access resolves via PropertyDef from the TypeDef.
Private field prefixing uses PropertyDef.isPrivate instead of
classes[currentClass]!!.privateProps set lookup.
---
5.4 — Migrate constructor dispatch (genCall)
File: CCodeGenExpr.kt (~line 715, ~200 lines)
Constructor names (e.g., "Vec2", "MyList_Int") come from NameExpr.name.
Resolution: classes.containsKey(name) → classInfoFor(KtcType.User(typeDef)).
C name emission: ci.flatName.
---
5.5 — Migrate is/as type checks
File: CCodeGenExpr.kt (lines 173–239)
IsCheckExpr and CastExpr currently use resolveTypeName(e.type) → String.
Change to resolveTypeName(e.type) → KtcType.
---
5.6 — Migrate operator dispatch
Files: CCodeGenExpr.kt (lines 88–410), CCodeGenStmts.kt (lines 664–698, 1682–1734)
All operator resolution: get, set, contains, containsKey, iterator,
next, hashCode. Change from classes[typeStr]?.methods?.find { ... } to
classInfoFor(type)?.methods?.find { ... }.
Verify: ./gradlew jar succeeds, 32 integration tests pass.
---
Phase 6: Eliminate symbolPrefix and pfx()
Goal: Zero calls to pfx() and symbolPrefix. All C name resolution
goes through TypeDef.flatName or property/function-specific mechanisms.
---
6.1 — Identify remaining pfx() callers
After Phase 3 migrations, the remaining pfx() calls fall into categories:
Category	Example
Top-level functions	pfx(funDecl.name)
Top-level properties	pfx(propName)
Function references	pfx(::nameRef)
Ad-hoc name construction	pfx("${base}_${suffix}")
Enum values	pfx(enumName)_VALUE
String buffer helpers	pfx("ktc_StrBuf")
---
### 6.2 — Add C name resolution for non-TypeDef symbols
For top-level functions: maintain a `funNames: Map<String, String>` (name → C name)
populated during `collectDecls` from the file prefix.
For top-level properties: `topPropNames: Map<String, String>` similarly.
For function references: resolve through funNames map.
---
6.3 — Remove pfx() function and symbolPrefix map
After all callers converted:
- Delete pfx() from CCodeGen.kt (lines 60–66)
- Delete symbolPrefix map from CCodeGen.kt (line 58)
- Delete symbolPrefix population from collectDecls() (lines 944–987)
- Delete symbolPrefix population from CCodeGenScan.kt (lines 272, 327)
Verify: ./gradlew jar succeeds, 32 integration tests pass.
Grep: zero matches for symbolPrefix\[ and pfx( in source (aside from definition).
---
Phase 7: Remove Bridge
Goal: stringToKtc is only called from resolveTypeName (AST input edge).
cTypeStr(KtcType) is only used for C string emission (output edge).
No ad-hoc string → KtcType conversions in internal code.
---
7.1 — Audit for direct stringToKtc calls
All internal type resolution must go through resolveTypeName(TypeRef) → KtcType.
Any remaining direct stringToKtc calls indicate string-based type construction
that needs refactoring.
---
7.2 — Simplify cType(TypeRef) path
cType(TypeRef) currently: typeToKtc(resolveTypeName(TypeRef)). Simplify to
direct pipeline: resolveTypeName(TypeRef) → KtcType → cTypeStr(KtcType).
---
7.3 — Rename stringToKtc → parseResolvedTypeName
Make it private to CCodeGenCTypes.kt. It becomes the internal implementation
of resolveTypeName, not a public bridge. Only called from one place.
---
Phase 8: Cleanup
---
8.1 — Remove dead string-based type utilities
Functions no longer called:
- isArrayType(str) — replaced by KtcType.isArray
- isValueNullableType(str) — replaced by KtcType.isValueNullable
- arrayElementCType(str) — replaced by Arr.elem.toCType()
- toStringPrimitiveMaxLen(str) — replaced by Prim.maxLen
- pointerClassName(str) — replaced by KtcType pattern matching
- isBuiltinType(str) — replaced by KtcType queries
- isPointerType(str) — replaced by KtcType.isPointer
- resolveTypeNameInner(t: TypeRef) — no longer needed
---
8.2 — Remove internalName from KtcType
internalName was needed for symbolPrefix lookups. After symbolPrefix is
removed, internalName is only needed if classes/objects/etc. maps still
use string keys. Remove when maps are re-keyed or helpers don't need it.
---
8.3 — Final verification
- All ~330 unit tests pass (./gradlew test)
- All 32 integration tests pass (./run_tests.ps1 -Skip unit)
- rg "symbolPrefix\[" src/main/kotlin/ returns zero results
- rg 'pfx\(' src/main/kotlin/ returns zero results (except definition, deleted)
- rg 'resolveTypeName' src/main/kotlin/ only in: function definition + parseResolvedTypeName
---
Progress
Phase 1: CoreType.kt ✅
- Prim, Str, Void, User, Arr, Ptr, Nullable, Func all defined
- from(TypeRef) builder, .toCType(), queries (.isArray, .isPointer, .elementType, etc.)
- 32 integration + ~330 unit tests pass
Phase 2: Bridge & User cleanup ✅
- Arr.ptr flag removed, OptArray removed
- User carries baseName, typeArgs, kind (UserKind), pkg, flatName
- stringToKtc uses user() helper for User creation with auto-pkg derivation
- cTypeStr(KtcType.User) uses flatName instead of pfx()
- cType(TypeRef) routes through typeToKtc → cTypeStr(KtcType) (passes @Size)
- 32 integration tests pass
Phase 3: TypeDef + PropertyDef Infrastructure
- [ ] 3.0 — Introduce PropertyDef, refactor ClassInfo/ObjInfo
- [ ] 3.1 — Introduce TypeDef interface + BuiltinTypeDef
- [ ] 3.2 — ClassInfo, ObjInfo, EnumInfo, IfaceInfo implement TypeDef
- [ ] 3.3 — Populate pkg on each TypeDef during collectDecls()
- [ ] 3.4 — Change KtcType.User to wrap TypeDef reference
- [ ] 3.5 — Update all User creation sites (remove user() helper)
- [ ] 3.6 — Replace pfx(name) with info.flatName in emit code
Phase 4: Typed Type Resolution
- [ ] 4.1 — Add resolveTypeNameKtc(TypeRef): KtcType
- [ ] 4.2 — Add TypeRef.resolveKtc() convenience
- [ ] 4.3 — Change scopes to store KtcType
- [ ] 4.4 — Migrate inferExprType to return KtcType
- [ ] 4.5 — Migrate inferDotType, inferMethodReturnType
- [ ] 4.6 — Migrate findOverload to compare KtcType
- [ ] 4.7 — Remove old resolveTypeName, rename resolveTypeNameKtc
Phase 5: Typed Dispatch
- [ ] 5.1 — Add KtcType-based lookup helpers
- [ ] 5.2 — Migrate genMethodCall
- [ ] 5.3 — Migrate genDot (field access)
- [ ] 5.4 — Migrate constructor dispatch (genCall)
- [ ] 5.5 — Migrate is/as type checks
- [ ] 5.6 — Migrate operator dispatch (get/set/contains/iterator)
Phase 6: Eliminate symbolPrefix and pfx()
- [ ] 6.1 — Identify remaining pfx() callers
- [ ] 6.2 — Add C name resolution for non-TypeDef symbols
- [ ] 6.3 — Remove pfx() and symbolPrefix
Phase 7: Remove Bridge
- [ ] 7.1 — Audit direct stringToKtc calls
- [ ] 7.2 — Simplify cType(TypeRef) path
- [ ] 7.3 — Rename stringToKtc → parseResolvedTypeName
Phase 8: Cleanup
- [ ] 8.1 — Remove dead string-based type utilities
- [ ] 8.2 — Remove internalName from KtcType (if possible)
- [ ] 8.3 — Final verification (all tests, zero symbolPrefix/pfx usage)