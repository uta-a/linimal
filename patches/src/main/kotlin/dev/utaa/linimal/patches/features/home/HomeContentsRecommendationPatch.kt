package dev.utaa.linimal.patches.features.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.utaa.linimal.patches.features.lineai.lineAiEntryPatch
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val CONTENT_MODEL = "Li42/c;"
private const val RECOMMENDATION_HOOK =
    "Ldev/utaa/linimal/extension/features/HomeRecommendationHooks;->shouldSuppress()Z"

/** recommendation placement ViewHolder の layout resource と superclass を組み合わせた class anchor。 */
private val recommendationViewHolderClassFingerprint = Fingerprint(
    name = "<init>",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf(
        "Landroid/widget/LinearLayout;",
        "Lcom/bumptech/glide/n;",
        "Landroidx/lifecycle/u0;",
        "Lb18/f;",
    ),
    filters = listOf(
        literal(0x7f0b11e6), // home_tab_contents_recommendation_placement
        methodCall(
            definingClass = "Landroid/view/View;",
            name = "findViewById",
            parameters = listOf("I"),
            returnType = "Landroid/view/View;",
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        fieldAccess(definingClass = "this", type = "Landroid/widget/LinearLayout;", opcode = Opcode.IPUT_OBJECT),
    ),
    custom = { _, classDef -> classDef.superclass == "Ll72/u;" },
)

/**
 * equality short-circuit → tracker cleanup → removeAllViews → model list iteration の binder。
 * field 名は使わず、cache field reference は matched instruction から動的に取得します。
 */
private val recommendationBindFingerprint = Fingerprint(
    classFingerprint = recommendationViewHolderClassFingerprint,
    returnType = "V",
    parameters = listOf("Ll72/j;"),
    filters = listOf(
        fieldAccess(definingClass = "this", type = CONTENT_MODEL, opcode = Opcode.IGET_OBJECT),
        methodCall(
            definingClass = "Lkotlin/jvm/internal/p;",
            name = "b",
            parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
            returnType = "Z",
            opcode = Opcode.INVOKE_STATIC,
        ),
        methodCall(
            definingClass = "Ll72/r;",
            parameters = emptyList(),
            returnType = "V",
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        methodCall(
            definingClass = "Landroid/view/ViewGroup;",
            name = "removeAllViews",
            parameters = emptyList(),
            returnType = "V",
            opcode = Opcode.INVOKE_VIRTUAL,
        ),
        fieldAccess(type = "Ljava/util/List;", opcode = Opcode.IGET_OBJECT),
    ),
)

val homeContentsRecommendationPatch = bytecodePatch {
    dependsOn(lineAiEntryPatch)

    execute {
        val matches = recommendationBindFingerprint.matchAllOrNull().orEmpty()
        if (matches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.HOME_CONTENTS_RECOMMENDATION),
                expectedTargetCount = 1,
                actualTargetCount = matches.size,
                reason = "HomeRecommendationBindNotUnique",
            )
            return@execute
        }

        val match = matches.single()
        val method = match.method
        val cacheIndex = match.instructionMatches[0].index
        val cleanupIndex = match.instructionMatches[3].index
        val listIndex = match.instructionMatches[4].index
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val cacheRead = instructions.getOrNull(cacheIndex) as? TwoRegisterInstruction
        val cacheField = (instructions.getOrNull(cacheIndex) as? ReferenceInstruction)
            ?.reference as? FieldReference
        val listRead = instructions.getOrNull(listIndex) as? OneRegisterInstruction
        val cleanupReference = (instructions.getOrNull(cleanupIndex) as? ReferenceInstruction)
            ?.reference as? MethodReference

        // cleanup の直後にだけ分岐します。ON では cache を null にして次回 bind を許可し、
        // OFF / hook failure では original list read と iterator を一切変更しません。
        if (
            cleanupIndex + 1 != listIndex ||
            instructions.firstOrNull()?.opcode != Opcode.MOVE_OBJECT_FROM16 ||
            instructions.getOrNull(cleanupIndex)?.opcode != Opcode.INVOKE_VIRTUAL ||
            cleanupReference?.definingClass != "Landroid/view/ViewGroup;" ||
            cleanupReference.name != "removeAllViews" ||
            cleanupReference.parameterTypes.isNotEmpty() ||
            cleanupReference.returnType != "V" ||
            cacheField?.definingClass != match.originalClassDef.type ||
            cacheField.type != CONTENT_MODEL ||
            cacheRead?.registerB != 0 ||
            listRead == null ||
            listRead.registerA !in 0..15 ||
            method.implementation == null
        ) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.HOME_CONTENTS_RECOMMENDATION),
                expectedTargetCount = 1,
                actualTargetCount = matches.size,
                reason = "HomeRecommendationBindInstructionShapeMismatch",
            )
            return@execute
        }

        val cacheFieldSmali = "${cacheField.definingClass}->${cacheField.name}:${cacheField.type}"
        method.addInstructionsWithLabels(
            cleanupIndex + 1,
            """
                invoke-static { }, $RECOMMENDATION_HOOK
                move-result v${listRead.registerA}
                if-eqz v${listRead.registerA}, :original
                const/4 v${listRead.registerA}, 0x0
                iput-object v${listRead.registerA}, v0, $cacheFieldSmali
                return-void
                :original
                nop
            """.trimIndent(),
        )
        recordFeatureStatus(
            listOf(PatchId.HOME_CONTENTS_RECOMMENDATION),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "HomeRecommendationClearedAfterCleanup",
        )
    }
}
