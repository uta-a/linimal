package dev.utaa.linimal.patches.features.home

import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeRecentHistoryPatchTest {
    @Test
    fun `source metadata resolves the package that holds the activity card composables`() {
        assertEquals(
            setOf("Lexample/card/"),
            activityCardPackagePrefixes(
                setOf("Lexample/card/Contents\$1;", "Lexample/card/Contents\$2;"),
            ),
        )
    }

    @Test
    fun `a package that cannot be derived leaves the target unresolved`() {
        assertTrue(activityCardPackagePrefixes(emptySet()).isEmpty())
        assertTrue(activityCardPackagePrefixes(setOf("LContents;")).isEmpty())
        // source file が複数 package に散っている場合は 1 つに絞り込めません。
        assertEquals(
            2,
            activityCardPackagePrefixes(setOf("Lexample/a/Contents;", "Lexample/b/Contents;")).size,
        )
    }

    @Test
    fun `only the recent history card renderer parameter order is accepted`() {
        assertTrue(
            isRecentHistoryCardRendererSignature(
                renderer(listOf(RECENT_HISTORY_CARD, "Ljava/util/List;", "I", "I", COMPOSER, "I")),
                setOf(RECENT_HISTORY_CARD),
                COMPOSER,
            ),
        )
        // 引数が増減しても、先頭の内容型と末尾の composer・changed の位置だけを見ます。
        assertTrue(
            isRecentHistoryCardRendererSignature(
                renderer(listOf(RECENT_HISTORY_CARD, COMPOSER, "I")),
                setOf(RECENT_HISTORY_CARD),
                COMPOSER,
            ),
        )
        // 26.14.0 では同じ marker を持つ内容型が複数あるため、候補は集合で受けます。
        assertTrue(
            isRecentHistoryCardRendererSignature(
                renderer(listOf(GLOBAL_HOME_RECENT_HISTORY_CARD, COMPOSER, "I")),
                setOf(RECENT_HISTORY_CARD, GLOBAL_HOME_RECENT_HISTORY_CARD),
                COMPOSER,
            ),
        )
        // 隣に並ぶ「サービス」card は別の内容型を取るため、対象外です。
        assertFalse(
            isRecentHistoryCardRendererSignature(
                renderer(listOf(FIXED_SERVICE_CARD, "Ljava/util/List;", COMPOSER, "I")),
                setOf(RECENT_HISTORY_CARD),
                COMPOSER,
            ),
        )
        // 内容型を第 1 引数に取らないものは、card 本体の renderer ではありません。
        assertFalse(
            isRecentHistoryCardRendererSignature(
                renderer(listOf("Ljava/lang/String;", RECENT_HISTORY_CARD, COMPOSER, "I")),
                setOf(RECENT_HISTORY_CARD),
                COMPOSER,
            ),
        )
        assertFalse(
            isRecentHistoryCardRendererSignature(
                renderer(listOf(RECENT_HISTORY_CARD, COMPOSER)),
                setOf(RECENT_HISTORY_CARD),
                COMPOSER,
            ),
        )
        assertFalse(
            isRecentHistoryCardRendererSignature(
                renderer(listOf(RECENT_HISTORY_CARD, "I", COMPOSER)),
                setOf(RECENT_HISTORY_CARD),
                COMPOSER,
            ),
        )
    }

    @Test
    fun `the recent history card renderer must be a single target`() {
        assertEquals(1, HOME_RECENT_HISTORY_TARGET_COUNT)
        assertEquals(3, RECENT_HISTORY_CARD_MINIMUM_PARAMETER_COUNT)
    }

    /** 「サービス」card と区別できる唯一の非難読化 marker のため、値そのものを固定します。 */
    @Test
    fun `the card content marker distinguishes recent history from the neighbouring service card`() {
        assertEquals("RecentlyUsedService(id=", RECENTLY_USED_SERVICE_MARKER)
    }

    @Test
    fun `no resolved renderer reports target not found and an ambiguous one reports error`() {
        val notFound = homeRecentHistoryUnappliedRecord(0, "HomeRecentHistorySourcePackageNotUnique")
        assertEquals(PatchId.HOME_RECENT_HISTORY, notFound.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, notFound.status)
        assertEquals(HOME_RECENT_HISTORY_TARGET_COUNT, notFound.expectedTargetCount)
        assertEquals(0, notFound.actualTargetCount)

        val ambiguous = homeRecentHistoryUnappliedRecord(2, "HomeRecentHistoryRendererNotUnique")
        assertEquals(PatchStatus.ERROR, ambiguous.status)
        assertEquals(2, ambiguous.actualTargetCount)
    }

    private fun renderer(parameters: List<String>) = ImmutableMethod(
        "Lexample/card/Renderer;",
        "a",
        parameters.map { ImmutableMethodParameter(it, null, null) },
        "V",
        0,
        null,
        null,
        null,
    )

    private companion object {
        /** 「最近の履歴」card の内容型に相当する stand-in です。 */
        const val RECENT_HISTORY_CARD = "Lexample/model/Card\$RecentlyUsedService;"

        /** Global Home 側にある、同じ marker を持つ内容型の stand-in です。 */
        const val GLOBAL_HOME_RECENT_HISTORY_CARD = "Lexample/model/GlobalCard\$RecentlyUsedService;"

        /** 隣に並ぶ「サービス」card の内容型に相当する stand-in です。 */
        const val FIXED_SERVICE_CARD = "Lexample/model/Card\$FixedService;"

        /** 難読化名は版ごとに変わるため、テストでは実物ではなく stand-in を使います。 */
        const val COMPOSER = "Lexample/compose/Composer;"
    }
}
