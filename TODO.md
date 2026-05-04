# Known Transpiler Limitations

## Parser

- **`override fun name() = expr`** — Non-block (expression-body) functions with `override` cause `Expected identifier but got OVERRIDE` error.
  Workaround: Use block body `override fun name(): T { return expr }`

## Type System

- **`Any` type** — Not supported. Using `Any` produces `unknown type name` in generated C.
  Workaround: Use explicit types instead.

- **`is` inside `when`** — `when (obj) { is String -> ... }` fails with `Expected expression, got ARROW`.
  Workaround: Use `if (obj is T)` pattern.

- **`is` smart-cast on nullable** — `if (y is String)` on a nullable `Any?` doesn't smart-cast inside the block; still sees the original type.
  Workaround: Explicit cast or separate variable.

- **`!= null` on Optional value types** — Comparing `Int?` (Optional struct) to `null` generates `!= NULL` which is a type mismatch in C.

## Interfaces

- **Nullable interface types (`Shape?`)** — Optional wrapper for interfaces not generated. Using `Shape?` produces `unknown type name X_Shape_Optional`.
  Workaround: Avoid nullable interface parameters.

- **`ArrayList<InterfaceType>`** — Storing interface-typed values in ArrayList doesn't work; the `contains` / `indexOf` comparisons fail with `invalid operands to binary ==` on interface types.

## Extensions

- **`this` in extension function body** — The keyword `this` doesn't map to `$self` in C.
  Workaround: Access receiver fields/properties directly by name.

- **`$self` in string templates** — `"$self!!!"` inside extension function tries to resolve `$self` as a Kotlin variable.

- **Generic extensions on interfaces** — `fun Map<K,V>.tryDispose()` is parsed as non-generic (type args treated as receiver args, not function type params). Monomorphization not wired up for this case.

## Vararg

- **Vararg in string templates** — `"${sum(1, 2, 3)}"` generates undeclared temp variables for the inline array.

## JsonTest

- **Broken by interface/Iterator changes** — `MutableList` type mismatch in `parseValue`, `printJsonValue`, `.dispose()` on int. Needs investigation.

## For-Loop

- **`until` range** — `for (i in 0..<v2.size)` may not parse correctly in all contexts.
  Workaround: Use `for (i in 0 until v2.size)`.

## Objects

- **Private `var` reassignment in object** — `count = 0` inside object method fails with `Val cannot be reassigned`. The private `var` isn't recognized as mutable in object context.

## `override val` in constructor

- **`class Foo(override val bar: Int) : Parent`** — Not supported. Use explicit property override in body.
