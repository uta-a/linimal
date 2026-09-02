package dev.utaa.linimal.patches.features.chat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import dev.utaa.linimal.patches.features.readwithoutreceipt.readWithoutReceiptMarkAsReadBlockPatch
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val OBJECT = "Ljava/lang/Object;"
private const val OBJECT_ARRAY = "[Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val CHAT_LIST_HEADER_HOOK =
    "Ldev/utaa/linimal/extension/features/ChatListHeaderHooks;->filterButtons(Ljava/util/List;)Ljava/util/List;"

/**
 * トーク一覧上部のボタンは Kotlin の sealed class 階層で表され、抽象基底 class は難読化されます。
 * 各 subclass は data class なので `toString()` の marker だけが非難読化のまま残ります。
 * AI Friends の marker から基底型を導くため、難読化名を patch へ書かずに済みます。
 */
private const val AI_FRIENDS_BUTTON_MARKER = "AiFriendsButtonStatus("

/** ボタン一覧を組み立てる suspend メソッドは 1 件だけです。絞り込めなければ一切注入しません。 */
internal const val CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT = 1

private val chatListHeaderButtonPatchIds = listOf(
    PatchId.CHAT_LIST_HEADER_AI_FRIENDS,
    PatchId.CHAT_LIST_HEADER_CALENDAR,
    PatchId.CHAT_LIST_HEADER_OPEN_CHAT,
)

private val aiFriendsButtonStatusFingerprint = Fingerprint(strings = listOf(AI_FRIENDS_BUTTON_MARKER))

/**
 * トーク一覧上部の AI Friends・カレンダー・オープンチャットのアイコンを、表示設定に応じて抑制します。
 *
 * <h2>対象の特定</h2>
 * <p>まず `AiFriendsButtonStatus(` という `toString()` marker を持つ data class を 1 件だけ選び、その
 * superclass をボタンの抽象基底型とします。次にその基底型の配列を `new-array` で作るメソッドを探し、
 * 1 件だけであることを確認します。実 APK ではボタン一覧を組み立てる suspend メソッドだけが該当します。</p>
 *
 * <h2>抑制の方法</h2>
 * <p>そのメソッドは配列を List へ変換して返す形で終わるため、末尾の `return-object` の直前で
 * 一覧を extension へ渡し、絞り込んだ List に差し替えます。ボタンごとの分岐を新しく作らないので、
 * 設定が OFF のときは元の List instance がそのまま返ります。</p>
 */
val chatListHeaderButtonsPatch = bytecodePatch {
    // 機能パッチは単一の直列チェーンを成し、この patch の後段に noOpProbePatch が続きます。
    dependsOn(readWithoutReceiptMarkAsReadBlockPatch)

    execute {
        val buttonBaseTypes = aiFriendsButtonStatusFingerprint.matchAllOrNull().orEmpty()
            .map { it.originalClassDef }
            .distinctBy { it.type }
            .mapNotNull { it.superclass }
            // 基底型が Object のままなら sealed 階層を掴めていないため、対象を広げずに諦めます。
            .filter { it != OBJECT }
            .toSet()
        if (buttonBaseTypes.size != CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT) {
            recordFeatureStatus(
                chatListHeaderButtonPatchIds,
                expectedTargetCount = CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT,
                actualTargetCount = buttonBaseTypes.size,
                reason = "ChatListHeaderButtonBaseTypeNotUnique",
            )
            return@execute
        }

        val buttonArrayType = "[${buttonBaseTypes.single()}"
        val builderFingerprint = Fingerprint(
            custom = { method, _ -> createsChatListHeaderButtonArray(method, buttonArrayType) },
        )
        val matches = builderFingerprint.matchAllOrNull().orEmpty()
        if (matches.size != CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT) {
            recordFeatureStatus(
                chatListHeaderButtonPatchIds,
                expectedTargetCount = CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT,
                actualTargetCount = matches.size,
                reason = "ChatListHeaderButtonBuilderNotUnique",
            )
            return@execute
        }

        val method = matches.single().method
        val implementation = method.implementation
        val injection = implementation?.let {
            chatListHeaderButtonsInjection(
                instructions = it.instructions.toList(),
                hasTryBlocks = it.tryBlocks.isNotEmpty(),
            )
        }
        if (injection == null) {
            // cardinality は揃っていても末尾の shape が崩れている場合は、何も変更しません。
            recordUnsafeFeatureStatus(
                chatListHeaderButtonPatchIds,
                expectedTargetCount = CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT,
                actualTargetCount = matches.size,
                reason = "ChatListHeaderButtonReturnShapeMismatch",
            )
            return@execute
        }

        method.addInstructions(
            injection.index,
            """
                invoke-static { v${injection.register} }, $CHAT_LIST_HEADER_HOOK
                move-result-object v${injection.register}
            """.trimIndent(),
        )
        recordFeatureStatus(
            chatListHeaderButtonPatchIds,
            expectedTargetCount = CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT,
            actualTargetCount = CHAT_LIST_HEADER_BUTTONS_TARGET_COUNT,
            reason = "ChatListHeaderButtonsFilteredBeforeReturn",
        )
    }
}

/** ボタンの基底型の配列を作るメソッドかどうか。一覧の組み立て元はこの `new-array` を必ず持ちます。 */
internal fun createsChatListHeaderButtonArray(method: Method, buttonArrayType: String): Boolean =
    method.implementation?.instructions?.toList().orEmpty().any { instruction ->
        instruction.opcode == Opcode.NEW_ARRAY &&
            ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type == buttonArrayType
    }

internal data class ChatListHeaderButtonsInjection(
    val index: Int,
    val register: Int,
)

/**
 * 末尾の「配列 → List の変換 → `move-result-object` → `return-object`」の並びを検証します。
 *
 * <p>suspend メソッドなので途中にも `return-object` がありますが、それらは coroutine の suspend 復帰用です。
 * 変換呼び出しから連続する末尾の 1 箇所だけを注入点とし、それ以外の shape は意図的に拒否します。
 * `invoke-static` は 4bit register しか取れないため、返り値の register が v15 を超える場合も拒否します。</p>
 */
internal fun chatListHeaderButtonsInjection(
    instructions: List<Instruction>,
    hasTryBlocks: Boolean,
): ChatListHeaderButtonsInjection? {
    if (hasTryBlocks || instructions.size < 3) {
        return null
    }

    val returnIndex = instructions.lastIndex
    val returnInstruction = instructions[returnIndex] as? OneRegisterInstruction ?: return null
    val resultMove = instructions[returnIndex - 1] as? OneRegisterInstruction ?: return null
    val conversion =
        (instructions[returnIndex - 2] as? ReferenceInstruction)?.reference as? MethodReference ?: return null

    if (
        instructions[returnIndex].opcode != Opcode.RETURN_OBJECT ||
        instructions[returnIndex - 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
        instructions[returnIndex - 2].opcode != Opcode.INVOKE_STATIC ||
        conversion.parameterTypes.map { it.toString() } != listOf(OBJECT_ARRAY) ||
        conversion.returnType != LIST ||
        returnInstruction.registerA != resultMove.registerA ||
        resultMove.registerA !in 0..15
    ) {
        return null
    }
    return ChatListHeaderButtonsInjection(index = returnIndex, register = resultMove.registerA)
}
