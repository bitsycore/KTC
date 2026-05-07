# Left To Do — Unit Test Coverage

## Added (5 new test files)

| File                          | Tests | Status                                                       |
|-------------------------------|-------|--------------------------------------------------------------|
| `ConstructorUnitTest.kt`      | 17    | ✅ All pass                                                   |
| `LambdaInlineUnitTest.kt`     | 16    | ❌ ~12 fail — need assertion fixes                            |
| `OperatorOverloadUnitTest.kt` | 13    | ❌ ~10 fail — `data` keyword, interface dispatch, assertions  |
| `CastUnitTest.kt`             | 8     | ❌ ~2 fail — `when` + `is` + `->` arrow issue, interface cast |
| `InitBlockUnitTest.kt`        | 4     | ⏸️ All `notYetImpl` (init blocks not emitted for classes)    |
| `DestructuringUnitTest.kt`    | 6     | ⏸️ 3 pass, 3 `notYetImpl`                                    |

## Tests that need fixing

### LambdaInlineUnitTest
- Inline comment format uses `Fun(Int)->Int` not `(Int) -> Int`
- String args in comment are `"World"` not `ktc_str("World")`
- `lambdaStandaloneErrors` — check exact error message
- `stdlibRunExpansion`, `stdlibWithExpansion` — check if these work via stdlib

### OperatorOverloadUnitTest
- `data` is a keyword — rename param to `arr` (done in some tests, not all)
- `operatorIterator` — `TODO()` might error; provide actual impl or skip
- Interface get/set tests — check interface dispatch works

### CastUnitTest
- `isCheckInWhen` — `when(s) { is Foo -> 1 }` fails because parser doesn't support `->` in when branches? Check lexer ARROW token
- `asCastToInterface` — check the `_as_` function naming convention

## Still missing (no tests at all)

| Feature                                            | Status                          | Notes                                     |
|----------------------------------------------------|---------------------------------|-------------------------------------------|
| `operator fun compareTo`                           | ❌ Not implemented in transpiler | No dispatch mechanism                     |
| `operator fun plus/minus/times/div/rem`            | ❌ Not implemented               | Arithmetic compiled directly, no dispatch |
| `operator fun rangeTo` (`..`)                      | ❌ Not implemented               | Only works for integer range loops        |
| `operator fun inc/dec`                             | ❌ Not implemented               |                                           |
| `operator fun unaryPlus/unaryMinus/not`            | ❌ Not implemented               |                                           |
| `operator fun invoke`                              | ❌ Not implemented               |                                           |
| Infix functions (custom, non-`to`)                 | ❌ Not tested                    | Could be tested for parsing               |
| `require()`, `check()`, `error()`                  | ❌ Not tested                    | Stdlib, may work                          |
| `use()` (Closeable)                                | ❌ Not tested                    |                                           |
| `filter`, `map`, `forEach` on stdlib               | ❌ Not tested                    | Integration-tested indirectly             |
| Visibility on constructors (`private constructor`) | ❌ Not tested                    | Parser parses it?                         |
| Sealed classes                                     | ❌ Probably not supported        |                                           |
| Type aliases (`typealias`)                         | ❌ Probably not supported        |                                           |
| Raw strings (`"""..."""`)                          | ❌ Not tested                    |                                           |
| `@Suppress`, `@Volatile`, `@Transient`             | ❌ Not tested                    |                                           |
| Multi-dollar string templates (`$$`)               | ❌ Not tested                    |                                           |
| `as?` safe cast                                    | ❌ Not implemented               | No parser/lexer support                   |
| Destructuring `val (a, b) = pair`                  | ❌ Not implemented               | Parser limitation                         |
| `componentN()` on data classes                     | ❌ Not implemented               | No synthetic methods                      |

## Key fixes needed before adding more tests

1. **Fix inline comment assertions** — use actual `Fun(Args)->Ret` format
2. **Fix `data` parameter name** — `data` is a keyword token, rename to `arr`/`values`
3. **Fix `when` with `is` + arrow** — investigate `->` parsing in when branches  
4. **Interface operator dispatch** — verify `vt->get/set` dispatch works
