package com.bitsycore.ktc.codegen.mapping

import com.bitsycore.ktc.codegen.CCodeGen
import com.bitsycore.ktc.types.KtcType

val primitiveArraySet = setOf(
    "ByteArray",
    "ShortArray",
    "IntArray",
    "LongArray",
    "FloatArray",
    "DoubleArray",
    "BooleanArray",
    "CharArray",
    "UByteArray",
    "UShortArray",
    "UIntArray",
    "ULongArray",
    "StringArray"
)

fun CCodeGen.arrayElementCType(arrType: String?): String = when (arrType) {
    "ByteArray" -> "ktc_Byte"
    "ShortArray" -> "ktc_Short"
    "IntArray" -> "ktc_Int"
    "LongArray" -> "ktc_Long"
    "FloatArray" -> "ktc_Float"
    "DoubleArray" -> "ktc_Double"
    "BooleanArray" -> "ktc_Bool"
    "CharArray" -> "ktc_Char"
    "UByteArray" -> "ktc_UByte"
    "UShortArray" -> "ktc_UShort"
    "UIntArray" -> "ktc_UInt"
    "ULongArray" -> "ktc_ULong"
    "StringArray" -> "ktc_String"
    "ByteOptArray" -> "ktc_Byte_Optional"
    "ShortOptArray" -> "ktc_Short_Optional"
    "IntOptArray" -> "ktc_Int_Optional"
    "LongOptArray" -> "ktc_Long_Optional"
    "FloatOptArray" -> "ktc_Float_Optional"
    "DoubleOptArray" -> "ktc_Double_Optional"
    "BooleanOptArray" -> "ktc_Bool_Optional"
    "CharOptArray" -> "ktc_Char_Optional"
    "UByteOptArray" -> "ktc_UByte_Optional"
    "UShortOptArray" -> "ktc_UShort_Optional"
    "UIntOptArray" -> "ktc_UInt_Optional"
    "ULongOptArray" -> "ktc_ULong_Optional"
    "StringOptArray" -> "ktc_String_Optional"
    else -> {
        if (arrType != null) {
            // Class array: "Vec2Array" → element type "game_Vec2"
            if (arrType.endsWith("Array") && arrType.length > 5) {
                val elem = arrType.removeSuffix("Array") // element type name
                if (classArrayTypes.contains(elem) || classes.containsKey(elem) || enums.containsKey(elem)) return typeFlatName(
                    elem
                )
            }
            // Nullable-element class array: "Vec2OptArray" → "pkg_Vec2_Optional"
            if (arrType.endsWith("OptArray") && arrType.length > 8) {
                val elem = arrType.removeSuffix("OptArray")
                if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return "${typeFlatName(elem)}_Optional"
            }
        }
        "ktc_Int"
    }
}

fun CCodeGen.arrayElementKtType(arrType: String?): String = when (arrType) {
    "ByteArray" -> "Byte"
    "ShortArray" -> "Short"
    "IntArray" -> "Int"
    "LongArray" -> "Long"
    "FloatArray" -> "Float"
    "DoubleArray" -> "Double"
    "BooleanArray" -> "Boolean"
    "CharArray" -> "Char"
    "UByteArray" -> "UByte"
    "UShortArray" -> "UShort"
    "UIntArray" -> "UInt"
    "ULongArray" -> "ULong"
    "StringArray" -> "String"
    "ByteOptArray" -> "Byte?"
    "ShortOptArray" -> "Short?"
    "IntOptArray" -> "Int?"
    "LongOptArray" -> "Long?"
    "FloatOptArray" -> "Float?"
    "DoubleOptArray" -> "Double?"
    "BooleanOptArray" -> "Boolean?"
    "CharOptArray" -> "Char?"
    "UByteOptArray" -> "UByte?"
    "UShortOptArray" -> "UShort?"
    "UIntOptArray" -> "UInt?"
    "ULongOptArray" -> "ULong?"
    "StringOptArray" -> "String?"
    else -> {
        if (arrType != null) {
            // Class array: "Vec2Array" → element Kotlin type "Vec2"
            if (arrType.endsWith("Array") && arrType.length > 5) {
                val elem = arrType.removeSuffix("Array") // element type name
                if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return elem
            }
            // Nullable-element class array: "Vec2OptArray" → "Vec2?"
            if (arrType.endsWith("OptArray") && arrType.length > 8) {
                val elem = arrType.removeSuffix("OptArray")
                if (classArrayTypes.contains(elem) || classes.containsKey(elem)) return "${elem}?"
            }
        }
        "Int"
    }
}

fun CCodeGen.primitiveToArrayType(vElem: String): String = when (vElem) {
    "Byte" -> "ByteArray"
    "Short" -> "ShortArray"
    "Int" -> "IntArray"
    "Long" -> "LongArray"
    "Float" -> "FloatArray"
    "Double" -> "DoubleArray"
    "Boolean" -> "BooleanArray"
    "Char" -> "CharArray"
    "UByte" -> "UByteArray"
    "UShort" -> "UShortArray"
    "UInt" -> "UIntArray"
    "ULong" -> "ULongArray"
    "String" -> "StringArray"
    else -> {
        classArrayTypes.add(vElem)
        "${vElem}Array"
    }
}

fun CCodeGen.primitiveToArrayOptionalType(vElem: String): String = when (vElem) {
    "Byte" -> "ByteOptArray"
    "Short" -> "ShortOptArray"
    "Int" -> "IntOptArray"
    "Long" -> "LongOptArray"
    "Float" -> "FloatOptArray"
    "Double" -> "DoubleOptArray"
    "Boolean" -> "BooleanOptArray"
    "Char" -> "CharOptArray"
    "UByte" -> "UByteOptArray"
    "UShort" -> "UShortOptArray"
    "UInt" -> "UIntOptArray"
    "ULong" -> "ULongOptArray"
    "String" -> "StringOptArray"
    else -> {
        classArrayTypes.add(vElem)
        "${vElem}OptArray"
    }
}

// ══ KtcType overloads ═══════════════════════════════════════════════

/** Extract element C type from an array KtcType: Ptr<Arr<Int>> → "ktc_Int". */
internal fun CCodeGen.arrayElementCTypeKtc(arrKtc: KtcType): String = arrKtc.asArr?.elem?.toCType() ?: "ktc_Int"

/** Extract element internal type from an array KtcType: Ptr<Arr<Int>> → "Int". */
internal fun CCodeGen.arrayElementKtTypeKtc(arrKtc: KtcType): String = arrKtc.asArr?.elem?.toInternalStr ?: "Int"