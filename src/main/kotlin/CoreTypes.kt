package com.bitsycore

/**
 * Typed representation of Kotlin-to-C types.
 * Replaces ad-hoc string-based type handling (endsWith("Array"), startsWith("Pair_"), etc.)
 * with structured, pattern-matchable types.
 *
 * During migration, string compatibility is maintained via:
 *   - `KtcType.from(typeRef, resolve)` to construct from TypeRef
 *   - `KtcType.toCType()` to get the C type string (replaces cTypeStr)
 *   - Queries like `isArray`, `isPointer`, `isNullable`, `elementType` replace string checks
 */
sealed class KtcType {

    // ── Primitive types ──────────────────────────────────────────────

    data class Prim(val kind: PrimKind) : KtcType() {
        override fun toCType(): String = when (kind) {
            PrimKind.Byte    -> "ktc_Byte"
            PrimKind.Short   -> "ktc_Short"
            PrimKind.Int     -> "ktc_Int"
            PrimKind.Long    -> "ktc_Long"
            PrimKind.UByte   -> "ktc_UByte"
            PrimKind.UShort  -> "ktc_UShort"
            PrimKind.UInt    -> "ktc_UInt"
            PrimKind.ULong   -> "ktc_ULong"
            PrimKind.Float   -> "ktc_Float"
            PrimKind.Double  -> "ktc_Double"
            PrimKind.Boolean -> "ktc_Bool"
            PrimKind.Char    -> "ktc_Char"
            PrimKind.Rune    -> "ktc_Rune"
        }
        val ktName: String get() = kind.name
    }

    enum class PrimKind { Byte, Short, Int, Long, UByte, UShort, UInt, ULong, Float, Double, Boolean, Char, Rune }

    // ── String ───────────────────────────────────────────────────────

    object Str : KtcType() {
        override fun toCType() = "ktc_String"
    }

    // ── void / Nothing ───────────────────────────────────────────────

    object Void : KtcType() {
        override fun toCType() = "void"
    }

    // ── User-defined class / interface / enum ────────────────────────

    data class User(
        val name: String,        // flat internal name (e.g. "Vec2", "Pair_Int_String")
        val typeArgs: List<KtcType> = emptyList(),
        val isData: Boolean = false,
        val isEnum: Boolean = false
    ) : KtcType() {
        override fun toCType(): String {
            val pfx = symbolPrefix?.invoke(name) ?: name
            return if (name.startsWith("Pair_") || name.startsWith("Triple_")) "ktc_$pfx" else pfx
        }
        companion object {
            var symbolPrefix: ((String) -> String)? = null
        }
    }

    // ── Array types ──────────────────────────────────────────────────

    data class Arr(
        val elem: KtcType,
        val ptr: Boolean = false,      // @Ptr Array<T> → T*
        val sized: Int? = null         // @Size(N) Array<T> → T[N]
    ) : KtcType() {
        override fun toCType(): String = when {
            sized != null -> "${elem.toCType()}[${sized}]"
            ptr          -> "${elem.toCType()}*"
            else         -> "ktc_ArrayTrampoline"
        }
    }

    // ── Raw pointer ──────────────────────────────────────────────────

    data class Ptr(val inner: KtcType) : KtcType() {
        override fun toCType() = "${inner.toCType()}*"
    }

    // ── Nullable wrapper ─────────────────────────────────────────────

    data class Nullable(val inner: KtcType) : KtcType() {
        override fun toCType(): String {
            val c = inner.toCType()
            return if (inner is Ptr || inner is Arr && inner.ptr) c else "ktc_${c}_Optional"
        }
    }

    // ── Function type ────────────────────────────────────────────────

    data class Func(val params: List<KtcType>, val ret: KtcType) : KtcType() {
        override fun toCType() = "void*"
    }

    // ── Optional array (nullable elements) ───────────────────────────

    data class OptArray(val elem: KtcType) : KtcType() {
        override fun toCType() = "${elem.toCType()}*"
    }

    // ── Abstract methods ─────────────────────────────────────────────

    abstract fun toCType(): String

    // ── Queries (replace string checks) ──────────────────────────────

    val isArray: Boolean get() = this is Arr
    val isPointer: Boolean get() = this is Ptr || (this is Arr && ptr)
    val isSizedArray: Boolean get() = this is Arr && sized != null
    val isPrimitive: Boolean get() = this is Prim
    val isString: Boolean get() = this is Str
    val isVoid: Boolean get() = this is Void

    val elementType: KtcType? get() = when (this) {
        is Arr -> elem
        is OptArray -> elem
        is Ptr -> inner
        is Nullable -> inner
        else -> null
    }

    /** Internal name for symbol prefix lookup (e.g. "Vec2", "Pair_Int_String"). */
    val internalName: String get() = when (this) {
        is Prim -> ktName
        is Str -> "String"
        is Void -> "void"
        is User -> name
        is Func -> "Fun"
        else -> toCType()
    }

    val nullable: KtcType get() = Nullable(this)

    // ── Static builder from TypeRef ──────────────────────────────────

    companion object {
        /**
         * Build a KtcType from a TypeRef, using resolveName to resolve type names.
         * resolveName: (String, List<String>) → String (calls resolveTypeName under the hood)
         */
        fun from(typeRef: TypeRef, resolveName: (String) -> String): KtcType {
            val resolved = resolveName(typeRef.name)
            val base = if (typeRef.typeArgs.isNotEmpty()) resolved else typeRef.name
            val isPtr = typeRef.annotations.any { it.name == "Ptr" }
            val sizeAnn = typeRef.annotations.find { it.name == "Size" }?.args?.getOrNull(0) as? IntLit

            val inner: KtcType = when {
                base == "String" -> Str
                base == "void" || base == "Nothing" -> Void
                base in primitiveNames -> Prim(PrimKind.valueOf(base))
                base == "StringBuffer" -> User("ktc_StrBuf")
                base == "RawArray" && typeRef.typeArgs.isNotEmpty() ->
                    Ptr(from(typeRef.typeArgs[0], resolveName))
                base == "Array" && typeRef.typeArgs.isNotEmpty() -> {
                    val elem = from(typeRef.typeArgs[0], resolveName)
                    Arr(elem, ptr = isPtr, sized = sizeAnn?.value?.toInt())
                }
                base.endsWith("Array") && base !in setOf("Array", "RawArray") -> {
                    // IntArray, ByteArray, UIntArray, etc.
                    val elemName = base.removeSuffix("Array")
                    val elem = from(TypeRef(elemName), resolveName)
                    Arr(elem, ptr = isPtr, sized = sizeAnn?.value?.toInt())
                }
                base.endsWith("OptArray") -> {
                    val elemName = base.removeSuffix("OptArray")
                    OptArray(from(TypeRef(elemName), resolveName))
                }
                base.startsWith("Pair_") || base.startsWith("Triple_") -> {
                    val typeArgNames = base.removePrefix("Pair_").removePrefix("Triple_").split("_")
                    val typeArgs = typeArgNames.map { from(TypeRef(it), resolveName) }
                    User(base, typeArgs)
                }
                base == "Any" -> User("Any")
                else -> User(resolved)
            }

            return if (isPtr && inner !is Arr) Ptr(inner)
                   else if (typeRef.nullable) Nullable(inner)
                   else inner
        }

        private val primitiveNames = PrimKind.entries.map { it.name }.toSet()
    }
}
