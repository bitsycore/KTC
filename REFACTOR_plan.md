# CCodeGen.kt Refactoring Plan

**Goal**: Split 7855-line monolith into ~9 focused files for maintainability.

**Approach**: Extension functions on `CCodeGen` class. All files in same `com.bitsycore` package.
State members changed from `private` to `internal` so extension functions in other files can access them.

## Target File Layout

```
src/main/kotlin/
├── CCodeGen.kt            (~300 lines)  Main class, state, orchestrator (generate, collectAndScan, dumpSemantics)
├── CCodeGenStructures.kt  (~150 lines)  Data classes (BodyProp, ClassInfo, EnumInfo, ObjInfo, FunSig, IfaceInfo, ActiveLambda, IteratorInfo, COutput)
├── CCodeGenDecls.kt       (~200 lines)  collectDecls(), collectDecl()
├── CCodeGenScan.kt        (~800 lines)  scanForClassArrayTypes(), scanForGenericInstantiations(), materializeGeneric*(), scanForGenericFunCalls(), computeGenericFunConcreteReturns(), matchTypeParam()
├── CCodeGenEmit.kt       (~1500 lines)  emitClass(), emitGenericClass(), emitMethod(), emitFun(), emitExtensionFun(), emitGenericFunInstantiations(), emitStarExtFunInstantiations(), emitEnum(), emitEnumValuesData(), emitObject(), emitInterface(), emitInterfaceVtable(), emitIfaceInfo(), emitInterfaceVtablesForClass(), emitTransitiveInterfaceVtables(), emitTopProp(), plus helpers
├── CCodeGenStmts.kt      (~1350 lines)  emitStmt(), emitVarDecl(), emitAssign(), emitReturn(), emitExprStmt(), emitInlineCall(), emitLambdaCall(), emitPrintlnStmt(), emitIfStmt(), emitWhenStmt(), emitFor(), findOperatorIterator(), plus helpers
├── CCodeGenExpr.kt       (~2120 lines)  genExpr(), genName(), genBin(), genCall(), genMethodCall(), genDot(), genSafeDot(), genNotNull(), genIfExpr(), genWhenExpr(), genStrTemplate(), genToString(), genPrintln(), genPrint(), genArrayOfExpr(), genNewArray(), genLValue(), plus helpers
├── CCodeGenInfer.kt       (~460 lines)  inferExprType(), inferCallType(), inferMethodReturnType(), inferDotType(), inferIndexType(), plus helpers
└── CCodeGenCTypes.kt      (~570 lines)  cTypeStr(), cType(), resolveTypeName(), resolveTypeNameInner(), expandParams(), expandCtorParams(), isArrayType(), arrayElementCType(), arrayElementKtType(), ensurePair/Triple/Tuple(), defaultVal(), printf helpers
```

## Step-by-Step Procedure

### Step 1: Create CCodeGenStructures.kt
- Move all data classes (BodyProp, ClassInfo, EnumInfo, ObjInfo, FunSig, IfaceInfo, ActiveLambda, IteratorInfo, COutput)
- Change from `private` to `internal` visibility
- Import in CCodeGen.kt

### Step 2: Change state visibility in CCodeGen.kt
- Change `private val/var` → `internal val/var` for all state members
- Keep constructor params `private val` (file, allFiles, etc.)
- Keep `prefix` as `private` (derived from file.pkg)

### Step 3: Create CCodeGenDecls.kt
- Move `collectDecls()` and `collectDecl()` as internal extension functions

### Step 4: Create CCodeGenScan.kt
- Move all scanning functions (scanFor*, materialize*, computeGenericFunConcreteReturns, matchTypeParam)

### Step 5: Create CCodeGenEmit.kt
- Move all emit* functions (emitClass through emitTopProp + helpers)

### Step 6: Create CCodeGenStmts.kt
- Move all emitStmt variants and helpers

### Step 7: Create CCodeGenExpr.kt
- Move genExpr and all gen* helpers

### Step 8: Create CCodeGenInfer.kt
- Move inferExprType and all infer* helpers

### Step 9: Create CCodeGenCTypes.kt
- Move cTypeStr, resolveTypeName, array helpers, printf helpers

### Step 10: Slim down CCodeGen.kt
- Remove moved functions, keep only class body with state + generate/collectAndScan/dumpSemantics

### Step 11: Run tests
- `./gradlew test` after each step
- `./run_tests.sh` integration tests at the end

## Progress

- [ ] Step 1: CCodeGenStructures.kt
- [ ] Step 2: State visibility
- [ ] Step 3: CCodeGenDecls.kt
- [ ] Step 4: CCodeGenScan.kt
- [ ] Step 5: CCodeGenEmit.kt
- [ ] Step 6: CCodeGenStmts.kt
- [ ] Step 7: CCodeGenExpr.kt
- [ ] Step 8: CCodeGenInfer.kt
- [ ] Step 9: CCodeGenCTypes.kt
- [ ] Step 10: Slim down CCodeGen.kt
- [ ] Step 11: Final test run
