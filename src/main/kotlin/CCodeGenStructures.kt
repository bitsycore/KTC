package com.bitsycore

/**
 * Data classes used by CCodeGen for symbol tables and code generation.
 * Extracted from CCodeGen.kt for maintainability.
 */

internal data class BodyProp(
    val name: String,
    val type: TypeRef,
    val init: Expr?,
    val line: Int = 0,
    val isPrivate: Boolean = false,
    val mutable: Boolean = true,
    val isPrivateSet: Boolean = false
)

internal data class ClassInfo(
    val name: String,
    val isData: Boolean,
    val ctorProps: List<Pair<String, TypeRef>>,
    val ctorPlainParams: List<Pair<String, TypeRef>> = emptyList(),
    val bodyProps: List<BodyProp> = emptyList(),
    val methods: MutableList<FunDecl> = mutableListOf(),
    val initBlocks: List<Block> = emptyList(),
    val typeParams: List<String> = emptyList(),
    val privateProps: Set<String> = emptySet(),
    val valCtorProps: Set<String> = emptySet(),
    val privateSetProps: Set<String> = emptySet()
) {
    val props: List<Pair<String, TypeRef>>
        get() = ctorProps + bodyProps.map { it.name to it.type }
    val isGeneric get() = typeParams.isNotEmpty()
    fun isValProp(name: String): Boolean =
        name in valCtorProps || bodyProps.any { it.name == name && !it.mutable }
}

internal data class EnumInfo(val name: String, val entries: List<String>)
internal data class ObjInfo(
    val name: String,
    val props: List<Pair<String, TypeRef>>,
    val methods: MutableList<FunDecl> = mutableListOf()
)

internal data class FunSig(val params: List<Param>, val returnType: TypeRef?)

internal data class IfaceInfo(
    val name: String,
    val methods: List<FunDecl>,
    val properties: List<PropDecl> = emptyList(),
    val typeParams: List<String> = emptyList(),
    val superInterfaces: List<TypeRef> = emptyList()
)

internal data class ActiveLambda(val expr: LambdaExpr, val paramTypes: List<String>)

internal data class IteratorInfo(val iterClass: String, val iterCType: String, val elemKtType: String, val isPointer: Boolean)

/** Output of code generation: C header (.h) and source (.c) strings. */
data class COutput(val header: String, val source: String)
