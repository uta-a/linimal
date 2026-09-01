package dev.utaa.linimal.patches.features.chat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.utaa.linimal.patches.features.navigation.mainTabsPatch
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val RESOURCES = "Landroid/content/res/Resources;"
private const val CHAT_MENU_ENUM = "Lfg1/a\$b;"
private const val CHAT_MENU_HOOK =
    "Ldev/utaa/linimal/extension/features/ChatMenuHooks;->shouldHide(Ljava/lang/Object;)Z"

private val chatMenuPatchIds = listOf(
    PatchId.CHAT_MENU_CALENDAR,
    PatchId.CHAT_MENU_LINE_GIFT,
    PatchId.CHAT_MENU_LINE_PAY,
)

/** それぞれの concrete item class を resource literal と enum anchor から先に特定します。 */
private val calendarItemClassFingerprint = chatMenuItemClassFingerprint(0x7f151798)
private val giftItemClassFingerprint = chatMenuItemClassFingerprint(0x7f150cce)
private val payItemClassFingerprint = chatMenuItemClassFingerprint(0x7f150cd8)

private fun chatMenuItemClassFingerprint(labelResource: Int) = Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(RESOURCES),
    filters = listOf(literal(labelResource)),
)

private val calendarConstructorFingerprint = chatMenuItemConstructorFingerprint(
    calendarItemClassFingerprint,
    "CALENDAR",
    0x7f080643,
)
private val giftConstructorFingerprint = chatMenuItemConstructorFingerprint(
    giftItemClassFingerprint,
    "GIFT",
    0x7f08070c,
)
private val payConstructorFingerprint = chatMenuItemConstructorFingerprint(
    payItemClassFingerprint,
    "PAY",
    null,
)

private fun chatMenuItemConstructorFingerprint(
    classFingerprint: Fingerprint,
    enumName: String,
    iconResource: Int?,
): Fingerprint = Fingerprint(
    classFingerprint = classFingerprint,
    name = "<init>",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = buildList {
        fieldAccess(
            definingClass = CHAT_MENU_ENUM,
            name = enumName,
            type = CHAT_MENU_ENUM,
            opcode = Opcode.SGET_OBJECT,
        ).also(::add)
        if (iconResource != null) {
            literal(iconResource).also(::add)
        }
        methodCall(
            parameters = listOf("Ln/c;", "I", "L", CHAT_MENU_ENUM, "L", "Z", "L", "I"),
            returnType = "V",
            opcode = Opcode.INVOKE_DIRECT_RANGE,
        ).also(::add)
    },
)

/** PAY class 内の fallback URI は label / enum anchor と併せて必須です。 */
private val payFallbackDeepLinkFingerprint = Fingerprint(
    filters = listOf(string("line://pay")),
)

/**
 * CALENDAR / GIFT / PAY item の共通 availability predicate。concrete class 名や predicate 名は使用せず、
 * item class anchors から導いた共通 superclass、signature、enum access と call sequence を組み合わせます。
 */
val chatPlusMenuPatch = bytecodePatch {
    dependsOn(mainTabsPatch)

    execute {
        val calendarConstructors = calendarConstructorFingerprint.matchAllOrNull().orEmpty()
        val giftConstructors = giftConstructorFingerprint.matchAllOrNull().orEmpty()
        val payConstructors = payConstructorFingerprint.matchAllOrNull().orEmpty()
        if (
            calendarConstructors.size != 1 ||
            giftConstructors.size != 1 ||
            payConstructors.size != 1
        ) {
            recordConcreteItemCounts(calendarConstructors.size, giftConstructors.size, payConstructors.size)
            return@execute
        }

        val calendarClass = calendarConstructors.single().originalClassDef
        val giftClass = giftConstructors.single().originalClassDef
        val payClass = payConstructors.single().originalClassDef
        val payDeepLinkMatches = payFallbackDeepLinkFingerprint.matchAllOrNull(payClass).orEmpty()
        if (payDeepLinkMatches.size != 1) {
            // shared predicate へ注入しないため、Calendar / Gift も適用済みとして記録しません。
            recordUnsafeFeatureStatus(
                listOf(PatchId.CHAT_MENU_CALENDAR, PatchId.CHAT_MENU_LINE_GIFT),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ChatMenuSharedPredicateNotResolved",
            )
            recordFeatureStatus(
                listOf(PatchId.CHAT_MENU_LINE_PAY),
                expectedTargetCount = 1,
                actualTargetCount = payDeepLinkMatches.size,
                reason = "ChatMenuPayDeepLinkNotUnique",
            )
            return@execute
        }

        val commonSuperclass = calendarClass.superclass
        if (
            commonSuperclass == null ||
            commonSuperclass != giftClass.superclass ||
            commonSuperclass != payClass.superclass ||
            calendarClass.type == giftClass.type ||
            calendarClass.type == payClass.type ||
            giftClass.type == payClass.type
        ) {
            recordUnsafeFeatureStatus(
                chatMenuPatchIds,
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ChatMenuItemSuperclassShapeMismatch",
            )
            return@execute
        }

        val predicateFingerprint = Fingerprint(
            definingClass = commonSuperclass,
            returnType = "Z",
            parameters = listOf("Lgi1/b;", "Lfg1/a;", "Lhg1/a\$a;"),
            filters = listOf(
                methodCall(
                    definingClass = "Ljava/lang/Object;",
                    name = "getClass",
                    returnType = "Ljava/lang/Class;",
                    opcode = Opcode.INVOKE_VIRTUAL,
                ),
                fieldAccess(definingClass = "this", type = CHAT_MENU_ENUM, opcode = Opcode.IGET_OBJECT),
                methodCall(
                    definingClass = "Ljava/lang/Enum;",
                    name = "ordinal",
                    returnType = "I",
                    opcode = Opcode.INVOKE_VIRTUAL,
                ),
                methodCall(
                    definingClass = "Ljava/util/Set;",
                    name = "contains",
                    parameters = listOf("Ljava/lang/Object;"),
                    returnType = "Z",
                    opcode = Opcode.INVOKE_INTERFACE,
                ),
            ),
        )
        val predicateMatches = predicateFingerprint.matchAllOrNull().orEmpty()
        if (predicateMatches.size != 1) {
            recordFeatureStatus(
                chatMenuPatchIds,
                expectedTargetCount = 1,
                actualTargetCount = predicateMatches.size,
                reason = "ChatMenuPredicateNotUnique",
            )
            return@execute
        }

        val match = predicateMatches.single()
        val method = match.method
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val first = instructions.firstOrNull() as? ReferenceInstruction
        val firstReference = first?.reference as? MethodReference
        val freeRegisters = (method.implementation?.registerCount ?: 0) - (method.parameterTypes.size + 1)
        if (
            first?.opcode != Opcode.INVOKE_VIRTUAL ||
            firstReference?.definingClass != "Ljava/lang/Object;" ||
            firstReference.name != "getClass" ||
            firstReference.parameterTypes.isNotEmpty() ||
            firstReference.returnType != "Ljava/lang/Class;" ||
            freeRegisters < 1
        ) {
            recordUnsafeFeatureStatus(
                chatMenuPatchIds,
                expectedTargetCount = 1,
                actualTargetCount = predicateMatches.size,
                reason = "ChatMenuPredicateInstructionShapeMismatch",
            )
            return@execute
        }

        method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $CHAT_MENU_HOOK
                move-result v0
                if-eqz v0, :original
                const/4 v0, 0x0
                return v0
                :original
                nop
            """.trimIndent(),
        )
        recordFeatureStatus(
            chatMenuPatchIds,
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "ChatMenuPredicateGuarded",
        )
    }
}

private fun recordConcreteItemCounts(calendar: Int, gift: Int, pay: Int) {
    recordFeatureStatus(
        listOf(PatchId.CHAT_MENU_CALENDAR),
        expectedTargetCount = 1,
        actualTargetCount = calendar,
        reason = "ChatMenuCalendarItemNotUnique",
    )
    recordFeatureStatus(
        listOf(PatchId.CHAT_MENU_LINE_GIFT),
        expectedTargetCount = 1,
        actualTargetCount = gift,
        reason = "ChatMenuGiftItemNotUnique",
    )
    recordFeatureStatus(
        listOf(PatchId.CHAT_MENU_LINE_PAY),
        expectedTargetCount = 1,
        actualTargetCount = pay,
        reason = "ChatMenuPayItemNotUnique",
    )
}
