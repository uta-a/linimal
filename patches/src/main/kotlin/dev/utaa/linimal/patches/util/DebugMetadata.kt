package dev.utaa.linimal.patches.util

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

/*
 * Kotlin coroutine の `@DebugMetadata` を、難読化された annotation 型名を patch へ書かずに扱います。
 *
 * annotation の**型名**は版ごとに変わります（26.11.0 の `Llb8/e;` は 26.14.0 で `Lqi8/f;`）。
 * 一方で annotation が持つ element 名（`c` = 宣言元 class 名、`f` = source file 名、`m` = method 名）と、
 * annotation class 自身の member の並びは Kotlin stdlib 側の定義なので版をまたいで変わりません。
 * そこで型名は実行時に導出し、各 patch は非難読化の class 名 / source file 名だけを持ちます。
 */

/** `@DebugMetadata` が持つ、非難読化のままの宣言元情報。 */
internal data class DebugMetadataSource(
    /** 宣言元の class 名（例: `com.linecorp...GcsHomeFeedPostModuleController$...$1$1`）。 */
    val className: String,
    /** 宣言元の source file 名（例: `GcsHomeFeedPostModuleController.kt`）。 */
    val sourceFile: String?,
)

private const val ANNOTATION_INTERFACE = "Ljava/lang/annotation/Annotation;"
private const val CLASS_NAME_ELEMENT = "c"
private const val SOURCE_FILE_ELEMENT = "f"

/**
 * `kotlin.coroutines.jvm.internal.DebugMetadata` の member。R8 は annotation の型名は変えますが、
 * element 名は annotation を使う側の encoded value が参照するため保たれます。
 */
private val DEBUG_METADATA_MEMBERS = setOf(
    Triple(CLASS_NAME_ELEMENT, STRING, 0),
    Triple(SOURCE_FILE_ELEMENT, STRING, 0),
    Triple("l", INT_ARRAY, 0),
    Triple("m", STRING, 0),
    Triple("v", INT, 0),
)

/**
 * `@DebugMetadata` の難読化された型 descriptor。member の並びが一致する annotation class が
 * 1 つに絞れない場合は null を返し、呼び出し側は何も注入しません。
 */
internal fun BytecodePatchContext.resolveDebugMetadataType(): String? {
    val candidates = mutableSetOf<String>()
    classDefForEach { classDef ->
        if (isDebugMetadataAnnotation(classDef)) {
            candidates += classDef.type
        }
    }
    return candidates.singleOrNull()
}

/** annotation class が `@DebugMetadata` かどうかを、member の名前・型・引数の数だけで判定します。 */
internal fun isDebugMetadataAnnotation(classDef: ClassDef): Boolean =
    classDef.interfaces.contains(ANNOTATION_INTERFACE) &&
        classDef.methods
            .map { method -> Triple(method.name, method.returnType, method.parameterTypes.size) }
            .toSet() == DEBUG_METADATA_MEMBERS

/** class に付いた `@DebugMetadata` の宣言元情報。付いていない場合は null を返します。 */
internal fun debugMetadataSource(classDef: ClassDef, debugMetadataType: String): DebugMetadataSource? {
    val annotation = classDef.annotations.firstOrNull { it.type == debugMetadataType } ?: return null
    val className = annotation.stringElement(CLASS_NAME_ELEMENT) ?: return null
    return DebugMetadataSource(className, annotation.stringElement(SOURCE_FILE_ELEMENT))
}

private fun Annotation.stringElement(name: String): String? =
    elements.firstOrNull { it.name == name }?.value.let { it as? StringEncodedValue }?.value
