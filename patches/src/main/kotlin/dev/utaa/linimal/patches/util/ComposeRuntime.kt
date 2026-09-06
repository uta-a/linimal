package dev.utaa.linimal.patches.util

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Compose runtime の難読化名を、patch へ書かずに実行時へ導出します。
 *
 * ホームの Compose 系 patch は composable の `shouldExecute` を書き換えるため、`Composer` 実装の
 * 難読化名を共有します。26.11.0 では `Lh3/f1;` / `A` / `l` / `Y`、26.14.0 では `Lh3/d1;` / `B` / `m` / `Z`
 * のように、版が上がるたびに 5 ファイルすべてを直す状態でした。ここへ集約し、非難読化の anchor
 * （Compose runtime が `skipToGroupEnd` で投げる例外メッセージ）から毎回導出します。
 */

/** Compose runtime の難読化 descriptor。[resolveComposeRuntime] だけが生成します。 */
internal data class ComposeRuntime(
    /** `androidx.compose.runtime.Composer` interface。composable の引数の型です。 */
    val composer: String,
    /** `ComposerImpl`。composable は `startRestartGroup` の戻り値へ直接 invoke-virtual します。 */
    val composerImpl: String,
    /** `Composer.shouldExecute(int, boolean)`。composable が本体を実行するかを返します。 */
    val shouldExecute: String,
    /** `Composer.skipToGroupEnd()`。LINE 自身が再 composition で本体を省くときの経路です。 */
    val skipToGroupEnd: String,
)

/**
 * `ComposerImpl.skipToGroupEnd()` が node 挿入中に投げる、非難読化のまま残る例外メッセージ。
 * Compose runtime の内部 assertion なので、難読化の対象になりません。
 */
private const val SKIP_TO_GROUP_END_MESSAGE = "No nodes can be emitted before calling skipAndEndGroup"

private val skipToGroupEndFingerprint = Fingerprint(
    parameters = emptyList(),
    returnType = VOID,
    strings = listOf(SKIP_TO_GROUP_END_MESSAGE),
)

/**
 * Compose runtime の難読化名。1 つに絞れない場合は null を返し、呼び出し側は何も注入しません。
 *
 * <p>導出の順序は次のとおりです。</p>
 * <ol>
 *   <li>例外メッセージから `skipToGroupEnd` を持つ method を 1 件だけ選び、その定義 class を
 *   `ComposerImpl`、method 名を `skipToGroupEnd` とします。</li>
 *   <li>`ComposerImpl` の中で `(int, boolean)` を取り `boolean` を返す method を `shouldExecute` とします。</li>
 *   <li>`ComposerImpl` の superclass / interface を辿り、同じ 2 つの契約を宣言している interface を
 *   `Composer` とします。</li>
 * </ol>
 */
internal fun BytecodePatchContext.resolveComposeRuntime(): ComposeRuntime? {
    val skipToGroupEnd = skipToGroupEndFingerprint.matchAllOrNull().orEmpty().singleOrNull() ?: return null
    val composerImpl = classDefByOrNull(skipToGroupEnd.originalClassDef.type) ?: return null
    val skipToGroupEndName = skipToGroupEnd.originalMethod.name

    val shouldExecuteName = composerImpl.methods
        .filter { method ->
            method.returnType == BOOLEAN &&
                method.parameterTypes.map(CharSequence::toString) == listOf(INT, BOOLEAN)
        }
        .map { it.name }
        .distinct()
        .singleOrNull() ?: return null

    val composer = composerInterface(composerImpl, shouldExecuteName, skipToGroupEndName) ?: return null
    return ComposeRuntime(
        composer = composer,
        composerImpl = composerImpl.type,
        shouldExecute = shouldExecuteName,
        skipToGroupEnd = skipToGroupEndName,
    )
}

/** `ComposerImpl` の型階層のうち、`shouldExecute` と `skipToGroupEnd` を宣言している唯一の interface。 */
private fun BytecodePatchContext.composerInterface(
    composerImpl: ClassDef,
    shouldExecute: String,
    skipToGroupEnd: String,
): String? {
    val visited = mutableSetOf(composerImpl.type)
    val pending = ArrayDeque<String>()
    composerImpl.superclass?.let { pending.add(it) }
    pending.addAll(composerImpl.interfaces)

    val candidates = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
        val type = pending.removeFirst()
        if (!visited.add(type)) {
            continue
        }
        val classDef = classDefByOrNull(type) ?: continue
        if (declaresComposerContract(classDef, shouldExecute, skipToGroupEnd)) {
            candidates += classDef.type
        }
        classDef.superclass?.let { pending.add(it) }
        pending.addAll(classDef.interfaces)
    }
    return candidates.singleOrNull()
}

private fun declaresComposerContract(classDef: ClassDef, shouldExecute: String, skipToGroupEnd: String): Boolean {
    val members = classDef.methods
        .map { method -> Triple(method.name, method.parameterTypes.map(CharSequence::toString), method.returnType) }
        .toSet()
    return members.contains(Triple(shouldExecute, listOf(INT, BOOLEAN), BOOLEAN)) &&
        members.contains(Triple(skipToGroupEnd, emptyList(), VOID))
}

/** `shouldExecute` の結果を受ける `if-eqz` の位置と、その結果が入っている register。 */
internal data class ComposeShouldExecuteGate(
    val branchIndex: Int,
    val shouldExecuteRegister: Int,
)

/** method の実装から [composeShouldExecuteGateShape] を求めます。実装が無い場合は null です。 */
internal fun composeShouldExecuteGate(
    method: Method,
    composeRuntime: ComposeRuntime,
): ComposeShouldExecuteGate? {
    val implementation = method.implementation ?: return null
    return composeShouldExecuteGateShape(
        instructions = implementation.instructions.toList(),
        hasTryBlocks = implementation.tryBlocks.isNotEmpty(),
        composeRuntime = composeRuntime,
    )
}

/**
 * `shouldExecute` → `move-result` → `if-eqz` の並びを検証します。
 *
 * <p>`shouldExecute` の戻り値は `Z` なので元の値は 0 か 1 に限られ、注入後に 1 へ戻しても
 * 情報は失われません。分岐先が `if-eqz` と一致する場合は注入が飛び越される可能性があるため、
 * その shape は意図的に拒否します。try block を持つ実装も対象外です。</p>
 *
 * <p>restartable composable であることの確認には `skipToGroupEnd` と `endRestartGroup` の呼出しを
 * 1 件ずつ数えます。`endRestartGroup` は method 名で照合しません。26.14.0 の `ComposerImpl` には
 * 同じ戻り値型を返す 0 引数 method が 3 つあり、`Composer` interface 側にも同名の method が無いため、
 * 名前を安定して導出できないためです。代わりに restartable composable 末尾の形
 * 「0 引数の composer 呼出し → `move-result-object` → `if-eqz`」だけで数えます。</p>
 */
internal fun composeShouldExecuteGateShape(
    instructions: List<Instruction>,
    hasTryBlocks: Boolean,
    composeRuntime: ComposeRuntime,
): ComposeShouldExecuteGate? {
    if (hasTryBlocks) {
        return null
    }
    val skipToGroupEndIndices = composerCallIndices(
        instructions = instructions,
        composerImpl = composeRuntime.composerImpl,
        name = composeRuntime.skipToGroupEnd,
        parameters = emptyList(),
        returnType = VOID,
    )
    if (skipToGroupEndIndices.size != 1) {
        return null
    }
    if (endRestartGroupIndices(instructions, composeRuntime.composerImpl).size != 1) {
        return null
    }

    val shouldExecuteIndex = composerCallIndices(
        instructions = instructions,
        composerImpl = composeRuntime.composerImpl,
        name = composeRuntime.shouldExecute,
        parameters = listOf(INT, BOOLEAN),
        returnType = BOOLEAN,
    ).singleOrNull() ?: return null
    val resultMove = instructions.getOrNull(shouldExecuteIndex + 1) as? OneRegisterInstruction ?: return null
    val branchIndex = shouldExecuteIndex + 2
    val branch = instructions.getOrNull(branchIndex) as? OneRegisterInstruction ?: return null

    if (
        instructions[shouldExecuteIndex + 1].opcode != Opcode.MOVE_RESULT ||
        instructions[branchIndex].opcode != Opcode.IF_EQZ ||
        branch.registerA != resultMove.registerA ||
        // 抑制と復元に使う const/4 は 4bit register しか取れません。
        resultMove.registerA !in 0..15
    ) {
        return null
    }

    if (isDivertedInjectionIndex(instructions, branchIndex)) {
        return null
    }
    return ComposeShouldExecuteGate(branchIndex, resultMove.registerA)
}

/**
 * 抑制のために注入する smali。元の結果が false のときは何もしません。true のときだけ hook を読み、
 * 抑制時は 0、非抑制時は `shouldExecute` が返すのと同じ 1 に戻します。
 */
internal fun composeShouldExecuteSuppression(gate: ComposeShouldExecuteGate, hook: String): String = """
    if-eqz v${gate.shouldExecuteRegister}, :linimalKeep
    invoke-static { }, $hook
    move-result v${gate.shouldExecuteRegister}
    if-eqz v${gate.shouldExecuteRegister}, :linimalRestore
    const/4 v${gate.shouldExecuteRegister}, 0x0
    goto :linimalKeep
    :linimalRestore
    const/4 v${gate.shouldExecuteRegister}, 0x1
    :linimalKeep
    nop
""".trimIndent()

private fun composerCallIndices(
    instructions: List<Instruction>,
    composerImpl: String,
    name: String,
    parameters: List<String>,
    returnType: String,
): List<Int> = instructions.indices.filter { index ->
    val reference = composerCallReference(instructions[index], composerImpl)
    reference != null &&
        reference.name == name &&
        reference.parameterTypes.map(CharSequence::toString) == parameters &&
        reference.returnType == returnType
}

private fun endRestartGroupIndices(instructions: List<Instruction>, composerImpl: String): List<Int> =
    instructions.indices.filter { index ->
        val reference = composerCallReference(instructions[index], composerImpl)
        reference != null &&
            reference.parameterTypes.isEmpty() &&
            reference.returnType.startsWith("L") &&
            instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            instructions.getOrNull(index + 2)?.opcode == Opcode.IF_EQZ
    }

private fun composerCallReference(instruction: Instruction, composerImpl: String): MethodReference? {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) {
        return null
    }
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return reference.takeIf { it.definingClass == composerImpl }
}
