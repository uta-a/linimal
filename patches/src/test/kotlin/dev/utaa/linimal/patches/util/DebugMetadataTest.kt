package dev.utaa.linimal.patches.util

import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotationElement
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@DebugMetadata` の型名は版ごとに変わります（26.11.0 の `Llb8/e;` は 26.14.0 で `Lqi8/f;`）。
 * member の並びから annotation class を判定できることと、そこから宣言元を読めることを固定します。
 */
class DebugMetadataTest {
    @Test
    fun `the annotation class is recognised by its members instead of its name`() {
        assertTrue(isDebugMetadataAnnotation(debugMetadataAnnotationClass("Lqi8/f;")))
        // 版が変わって型名だけが変わっても、同じ判定で見つかります。
        assertTrue(isDebugMetadataAnnotation(debugMetadataAnnotationClass("Llb8/e;")))
    }

    @Test
    fun `an annotation with different members is not debug metadata`() {
        assertFalse(
            isDebugMetadataAnnotation(
                annotationClass(
                    "Lexample/Other;",
                    listOf(member("c", STRING), member("f", STRING)),
                ),
            ),
        )
        // annotation でない class は、member が一致していても対象外です。
        assertFalse(
            isDebugMetadataAnnotation(
                ImmutableClassDef(
                    "Lexample/NotAnAnnotation;",
                    0,
                    "Ljava/lang/Object;",
                    emptyList(),
                    null,
                    emptyList(),
                    emptyList(),
                    debugMetadataMembers(),
                ),
            ),
        )
    }

    @Test
    fun `the declaring class and source file are read from the annotation`() {
        val source = debugMetadataSource(annotatedClass("Lqi8/f;"), "Lqi8/f;")

        assertEquals("com.linecorp.example.ExampleKt\$Example\$1\$1", source?.className)
        assertEquals("Example.kt", source?.sourceFile)
    }

    @Test
    fun `a class without the annotation has no source`() {
        assertNull(debugMetadataSource(annotatedClass("Lqi8/f;"), "Llb8/e;"))
    }

    private fun debugMetadataAnnotationClass(type: String): ClassDef =
        annotationClass(type, debugMetadataMembers())

    private fun debugMetadataMembers(): List<ImmutableMethod> = listOf(
        member("c", STRING),
        member("f", STRING),
        member("l", INT_ARRAY),
        member("m", STRING),
        member("v", INT),
    )

    private fun annotationClass(type: String, members: List<ImmutableMethod>): ClassDef = ImmutableClassDef(
        type,
        0,
        "Ljava/lang/Object;",
        listOf("Ljava/lang/annotation/Annotation;"),
        null,
        emptyList(),
        emptyList(),
        members,
    )

    private fun annotatedClass(annotationType: String): ClassDef = ImmutableClassDef(
        "Lexample/Continuation;",
        0,
        "Ljava/lang/Object;",
        emptyList(),
        null,
        listOf(
            ImmutableAnnotation(
                0,
                annotationType,
                listOf(
                    ImmutableAnnotationElement(
                        "c",
                        ImmutableStringEncodedValue("com.linecorp.example.ExampleKt\$Example\$1\$1"),
                    ),
                    ImmutableAnnotationElement("f", ImmutableStringEncodedValue("Example.kt")),
                    ImmutableAnnotationElement("m", ImmutableStringEncodedValue("invokeSuspend")),
                ),
            ),
        ),
        emptyList(),
        emptyList(),
    )

    private fun member(name: String, returnType: String) = ImmutableMethod(
        "Lexample/Annotation;",
        name,
        emptyList(),
        returnType,
        0,
        null,
        null,
        null,
    )
}
