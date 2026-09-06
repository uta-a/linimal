package dev.utaa.linimal.patches.features.home

import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeTrendingPatchTest {
    /** carousel 版と区別できる唯一の非難読化 marker のため、値そのものを固定します。 */
    @Test
    fun `the view data marker identifies the single matome module`() {
        assertEquals("GcsHomeFeedMatomeSingle(item=", MATOME_VIEW_DATA_MARKER)
    }

    @Test
    fun `only the five argument module renderer is accepted`() {
        assertTrue(
            isMatomeModuleRendererSignature(
                renderer("Ljava/lang/String;", VIEW_DATA, MODULE_STATE, COMPOSER, "I"),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        // module state の型は版ごとに変わるため、位置だけを見て型は問いません。
        assertTrue(
            isMatomeModuleRendererSignature(
                renderer("Ljava/lang/String;", VIEW_DATA, "Lexample/other/ModuleState;", COMPOSER, "I"),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        // 話題枠の compose body は 5 引数版だけです。引数の数や並びが違うものは対象にしません。
        assertFalse(
            isMatomeModuleRendererSignature(renderer(VIEW_DATA, MODULE_STATE, COMPOSER, "I"), VIEW_DATA, COMPOSER),
        )
        assertFalse(
            isMatomeModuleRendererSignature(
                renderer("Ljava/lang/String;", "Lexample/home/CarouselViewData;", MODULE_STATE, COMPOSER, "I"),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        assertFalse(
            isMatomeModuleRendererSignature(
                renderer("Ljava/lang/String;", VIEW_DATA, MODULE_STATE, COMPOSER, "J"),
                VIEW_DATA,
                COMPOSER,
            ),
        )
    }

    @Test
    fun `only one target is expected and nothing is injected without it`() {
        assertEquals(1, HOME_TRENDING_TARGET_COUNT)

        val notFound = homeTrendingUnappliedRecord(0, "HomeMatomeModuleViewDataNotUnique")
        assertEquals(PatchId.HOME_MATOME_SINGLE_MODULE, notFound.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, notFound.status)
        assertEquals(HOME_TRENDING_TARGET_COUNT, notFound.expectedTargetCount)
        assertEquals(0, notFound.actualTargetCount)

        // 1 件に絞り込めていない場合は raw match count を残した ERROR にします。
        val ambiguous = homeTrendingUnappliedRecord(2, "HomeMatomeModuleRendererNotUnique")
        assertEquals(PatchStatus.ERROR, ambiguous.status)
        assertEquals(2, ambiguous.actualTargetCount)
    }

    private fun renderer(vararg parameters: String): Method = ImmutableMethod(
        "Lexample/home/MatomeModule;",
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
        const val VIEW_DATA = "Lexample/home/MatomeSingleViewData;"
        const val MODULE_STATE = "Lexample/home/ModuleState;"
        const val COMPOSER = "Lexample/compose/Composer;"
    }
}
