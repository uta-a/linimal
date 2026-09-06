package dev.utaa.linimal.patches.features.home

import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 対象の取り違えを 2 度繰り返した箇所です。1 度目は難読化された class 名を主要条件にして
 * 別 package の全画面動画プレイヤーを掴み、2 度目は Material3 の CircularProgressIndicator の
 * うち**確定進捗版**の引数の並びを条件にしてしまい、実機のスピナー（LINE Design System の
 * spinner）に一致しませんでした。ここでは spinner と renderer の shape 判定を固定します。
 */
class HomeFeedLoadingIndicatorPatchTest {

    @Test
    fun `only the LDS spinner signature is accepted`() {
        // (size, Modifier, Boolean, Composer, $$changed, $$default)
        assertTrue(
            isLdsSpinnerSignature(
                renderer(listOf("Lexample/Size;", MODIFIER, "Z", COMPOSER, "I", "I")),
                COMPOSER,
            ),
        )
        // Material3 の確定進捗版はこの並びではありません。取り違えると実機で何も起きません。
        assertFalse(
            isLdsSpinnerSignature(
                renderer(listOf("Lexample/Fn;", MODIFIER, "J", "J", "I", "F", "Lexample/Fn1;", COMPOSER, "I")),
                COMPOSER,
            ),
        )
        // Modifier / Boolean / Composer の位置が違えば対象外です。
        assertFalse(
            isLdsSpinnerSignature(
                renderer(listOf("Lexample/Size;", "Z", MODIFIER, COMPOSER, "I", "I")),
                COMPOSER,
            ),
        )
    }

    @Test
    fun `only the loading renderer signature is accepted`() {
        // 読み込み表示の renderer は view data を持たず、Modifier と Composer と $$changed だけを取ります。
        assertTrue(isLoadingIndicatorRendererSignature(renderer(listOf(MODIFIER, COMPOSER, "I")), MODIFIER, COMPOSER))
        assertFalse(isLoadingIndicatorRendererSignature(renderer(listOf("I", COMPOSER)), MODIFIER, COMPOSER))
        assertFalse(isLoadingIndicatorRendererSignature(renderer(listOf(MODIFIER, COMPOSER)), MODIFIER, COMPOSER))
        assertFalse(
            isLoadingIndicatorRendererSignature(
                renderer(listOf(MODIFIER, COMPOSER, "I", "I")),
                MODIFIER,
                COMPOSER,
            ),
        )
    }

    @Test
    fun `the loading indicator renderer must be a single target`() {
        assertEquals(1, HOME_FEED_LOADING_INDICATOR_TARGET_COUNT)
    }

    @Test
    fun `no resolved renderer reports target not found and an ambiguous one reports error`() {
        val notFound = homeFeedLoadingIndicatorUnappliedRecord(0, "HomeFeedLoadingIndicatorSourcePackageNotUnique")
        assertEquals(PatchId.HOME_FEED_LOADING_INDICATOR, notFound.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, notFound.status)
        assertEquals(HOME_FEED_LOADING_INDICATOR_TARGET_COUNT, notFound.expectedTargetCount)
        assertEquals(0, notFound.actualTargetCount)

        val ambiguous = homeFeedLoadingIndicatorUnappliedRecord(2, "HomeFeedLoadingIndicatorRendererNotUnique")
        assertEquals(PatchStatus.ERROR, ambiguous.status)
        assertEquals(2, ambiguous.actualTargetCount)
    }

    /**
     * 呼び出し関係の突き合わせが overload を区別することを固定します。定義クラスと名前だけを
     * キーにすると、同じクラスの別 overload を同一視して対象を取り違えます。
     */
    @Test
    fun `the caller matching key distinguishes overloads`() {
        val modifierAndComposer = methodKey("Lexample/q;", "a", listOf(MODIFIER, COMPOSER, "I"), "V")
        val composerOnly = methodKey("Lexample/q;", "a", listOf(COMPOSER, "I"), "V")
        val differentReturn = methodKey("Lexample/q;", "a", listOf(MODIFIER, COMPOSER, "I"), "Ljava/lang/Object;")

        assertNotEquals(modifierAndComposer, composerOnly)
        assertNotEquals(modifierAndComposer, differentReturn)
        assertEquals(modifierAndComposer, methodKey("Lexample/q;", "a", listOf(MODIFIER, COMPOSER, "I"), "V"))
    }

    private fun renderer(parameters: List<String>) = ImmutableMethod(
        "Lexample/spinner/Renderer;",
        "a",
        parameters.map { ImmutableMethodParameter(it, null, null) },
        "V",
        0,
        null,
        null,
        null,
    )

    private companion object {
        /** 難読化名は版ごとに変わるため、テストでは実物ではなく stand-in を使います。 */
        const val MODIFIER = "Lexample/compose/Modifier;"
        const val COMPOSER = "Lexample/compose/Composer;"
    }
}
