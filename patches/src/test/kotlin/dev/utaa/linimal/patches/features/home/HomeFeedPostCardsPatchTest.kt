package dev.utaa.linimal.patches.features.home

import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeFeedPostCardsPatchTest {
    @Test
    fun `every post module must resolve before anything is injected`() {
        assertEquals(3, HOME_FEED_POST_CARDS_TARGET_COUNT)
    }

    @Test
    fun `only the five argument module renderer is accepted`() {
        assertTrue(
            isModuleRendererSignature(
                renderer("Ljava/lang/String;", VIEW_DATA, MODULE_STATE, COMPOSER, "I"),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        // module state の型は版ごとに変わるため、位置だけを見て型は問いません。
        assertTrue(
            isModuleRendererSignature(
                renderer("Ljava/lang/String;", VIEW_DATA, "Lexample/other/ModuleState;", COMPOSER, "I"),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        // module id を取らない特集枠の renderer は対象外です。
        assertFalse(
            isModuleRendererSignature(renderer(VIEW_DATA, MODULE_STATE, COMPOSER, "I"), VIEW_DATA, COMPOSER),
        )
        // 別 module の view data を取るものは対象外です。
        assertFalse(
            isModuleRendererSignature(
                renderer("Ljava/lang/String;", "Lexample/home/OtherViewData;", MODULE_STATE, COMPOSER, "I"),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        assertFalse(
            isModuleRendererSignature(
                renderer("Ljava/lang/String;", VIEW_DATA, MODULE_STATE, COMPOSER, "J"),
                VIEW_DATA,
                COMPOSER,
            ),
        )
    }

    @Test
    fun `no resolved module reports target not found and a partial one reports partial`() {
        val notFound = homeFeedPostCardsUnappliedRecord(0, "HomeFeedPostModuleViewDataNotUnique")
        assertEquals(PatchId.HOME_FEED_POST_CARDS, notFound.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, notFound.status)
        assertEquals(HOME_FEED_POST_CARDS_TARGET_COUNT, notFound.expectedTargetCount)
        assertEquals(0, notFound.actualTargetCount)

        val partial = homeFeedPostCardsUnappliedRecord(2, "HomeFeedPostRendererNotUnique")
        assertEquals(PatchStatus.PARTIAL, partial.status)
        assertEquals(2, partial.actualTargetCount)
    }

    private fun renderer(vararg parameters: String) = ImmutableMethod(
        "Lexample/home/PostModule;",
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
        const val VIEW_DATA = "Lexample/home/PostViewData;"
        const val MODULE_STATE = "Lexample/home/ModuleState;"
        const val COMPOSER = "Lexample/compose/Composer;"
    }
}
