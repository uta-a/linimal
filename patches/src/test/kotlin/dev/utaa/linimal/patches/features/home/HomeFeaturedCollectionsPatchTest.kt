package dev.utaa.linimal.patches.features.home

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.PatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeFeaturedCollectionsPatchTest {
    @Test
    fun `source metadata resolves the package that holds the featured grid composables`() {
        assertEquals(
            setOf("Lexample/home/"),
            featuredGridPackagePrefixes(
                setOf("Lexample/home/Grid\$1;", "Lexample/home/GridPost\$1;"),
            ),
        )
    }

    @Test
    fun `a package that cannot be derived leaves the target unresolved`() {
        assertTrue(featuredGridPackagePrefixes(emptySet()).isEmpty())
        assertTrue(featuredGridPackagePrefixes(setOf("LGrid;")).isEmpty())
        // source file が複数 package に散っている場合は 1 つに絞り込めません。
        assertEquals(
            2,
            featuredGridPackagePrefixes(setOf("Lexample/a/Grid;", "Lexample/b/Grid;")).size,
        )
    }

    /** 特集枠 module を他 module から区別できる唯一の非難読化 marker のため、値そのものを固定します。 */
    @Test
    fun `the view data marker identifies the featured grid module`() {
        assertEquals("GcsHomeFeedUnitShortFormGrid(id=", FEATURED_GRID_VIEW_DATA_MARKER)
    }

    @Test
    fun `only the module renderer parameter order is accepted`() {
        assertTrue(
            isFeaturedCollectionsRendererSignature(
                renderer(listOf(VIEW_DATA, MODULE_STATE, COMPOSER, "I")),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        // module state の型は版ごとに変わるため、位置だけを見て型は問いません。
        assertTrue(
            isFeaturedCollectionsRendererSignature(
                renderer(listOf(VIEW_DATA, "Lexample/other/ModuleState;", COMPOSER, "I")),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        // 投稿カード module のように view data の前に module id を取るものは対象外です。
        assertFalse(
            isFeaturedCollectionsRendererSignature(
                renderer(listOf("Ljava/lang/String;", VIEW_DATA, MODULE_STATE, COMPOSER, "I")),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        // 別 module の view data を取るものは対象外です。
        assertFalse(
            isFeaturedCollectionsRendererSignature(
                renderer(listOf("Lexample/home/OtherViewData;", MODULE_STATE, COMPOSER, "I")),
                VIEW_DATA,
                COMPOSER,
            ),
        )
        assertFalse(
            isFeaturedCollectionsRendererSignature(
                renderer(listOf(VIEW_DATA, MODULE_STATE, COMPOSER)),
                VIEW_DATA,
                COMPOSER,
            ),
        )
    }

    @Test
    fun `only grid state types built by the renderer are counted`() {
        val method = renderer(
            parameters = listOf(VIEW_DATA, MODULE_STATE, COMPOSER, "I"),
            instructions = listOf(
                newInstance("Lexample/grid/GridState;"),
                newInstance("Lexample/grid/CardState;"),
                newInstance("Lexample/grid/GridState;"),
                newInstance("Lexample/other/Unrelated;"),
            ),
        )

        assertEquals(
            setOf("Lexample/grid/GridState;", "Lexample/grid/CardState;"),
            featuredGridStateTypes(method, "Lexample/grid/"),
        )
        assertTrue(featuredGridStateTypes(method, "Lexample/grid/").size >= FEATURED_GRID_STATE_TYPE_MINIMUM)
        assertTrue(featuredGridStateTypes(method, "Lexample/missing/").isEmpty())
    }

    @Test
    fun `the featured grid module renderer must be a single target`() {
        assertEquals(1, HOME_FEATURED_COLLECTIONS_TARGET_COUNT)
    }

    @Test
    fun `no resolved renderer reports target not found and an ambiguous one reports error`() {
        val notFound = homeFeaturedCollectionsUnappliedRecord(0, "HomeFeaturedGridSourcePackageNotUnique")
        assertEquals(PatchId.HOME_FEATURED_COLLECTIONS, notFound.patchId)
        assertEquals(PatchStatus.TARGET_NOT_FOUND, notFound.status)
        assertEquals(HOME_FEATURED_COLLECTIONS_TARGET_COUNT, notFound.expectedTargetCount)
        assertEquals(0, notFound.actualTargetCount)

        val ambiguous = homeFeaturedCollectionsUnappliedRecord(2, "HomeFeaturedCollectionsRendererNotUnique")
        assertEquals(PatchStatus.ERROR, ambiguous.status)
        assertEquals(2, ambiguous.actualTargetCount)
    }

    private fun renderer(
        parameters: List<String>,
        instructions: List<Instruction> = emptyList(),
    ) = ImmutableMethod(
        "Lexample/home/Renderer;",
        "a",
        parameters.map { ImmutableMethodParameter(it, null, null) },
        "V",
        0,
        null,
        null,
        if (instructions.isEmpty()) {
            null
        } else {
            ImmutableMethodImplementation(8, instructions, null, null)
        },
    )

    private fun newInstance(type: String) =
        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(type))

    private companion object {
        /** 難読化名は版ごとに変わるため、テストでは実物ではなく stand-in を使います。 */
        const val VIEW_DATA = "Lexample/home/ShortFormGridViewData;"
        const val MODULE_STATE = "Lexample/home/ModuleState;"
        const val COMPOSER = "Lexample/compose/Composer;"
    }
}
