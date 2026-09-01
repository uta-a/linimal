package dev.utaa.linimal.patches.core

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.patchStatusCollector

private const val LINE_APPLICATION = "Ljp/naver/line/android/LineApplication;"
private const val ANDROID_APPLICATION = "Landroid/app/Application;"
private const val KOTLIN_UNIT = "Lkotlin/Unit;"
private const val BOOTSTRAP_METHOD =
    "Ldev/utaa/linimal/extension/core/LinimalBootstrap;->initialize(Landroid/content/Context;)V"

/**
 * LINE の Application 初期化本体を、難読化名ではなく次の複合条件で特定します。
 * すなわち Application を継承する宣言クラス、static な signature、`Application.onCreate` の呼び出し、
 * process を判定する `Process.myUid`、および初期化順序を示す 2 つの文字列です。
 */
internal val lineApplicationInitializeFingerprint = Fingerprint(
    definingClass = LINE_APPLICATION,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = KOTLIN_UNIT,
    parameters = listOf(LINE_APPLICATION),
    filters = listOf(
        methodCall("$ANDROID_APPLICATION->onCreate()V", Opcode.INVOKE_SUPER),
        methodCall("Landroid/os/Process;->myUid()I", Opcode.INVOKE_STATIC),
        string("ApplicationGraph.init"),
        string("LineApplication.disableSystemOutAndErr"),
    ),
    custom = { _, classDef -> classDef.superclass == ANDROID_APPLICATION },
)

/** LINE の process 判定を通過した後に、Linimal の設定を初期化します。 */
val linimalBootstrapPatch = bytecodePatch {
    dependsOn(linimalExtensionMergePatch)

    execute {
        val matches = lineApplicationInitializeFingerprint.matchAllOrNull().orEmpty()
        if (matches.size != 1) {
            bootstrapFailure("BootstrapFingerprintMismatch", matches.size)
        }

        val match = matches.single()
        val method = match.method
        val graphInitIndex = match.instructionMatches[2].index
        val uidGuardIndex = match.instructionMatches[1].index
        val graphInitInstruction = match.instructionMatches[2].instruction
        val implementation = method.implementation
            ?: bootstrapFailure("BootstrapImplementationMissing", 0)

        // process 判定より後、かつ ApplicationGraph の初期化より前であることを確認します。
        // この位置では p0 がまだ Application を保持しています。
        if (
            graphInitIndex <= uidGuardIndex ||
            graphInitInstruction.opcode != Opcode.CONST_STRING ||
            method.parameterTypes.size != 1 ||
            implementation.registerCount < method.parameterTypes.size
        ) {
            bootstrapFailure("BootstrapInstructionShapeMismatch", 0)
        }

        method.addInstructions(graphInitIndex, "invoke-static { p0 }, $BOOTSTRAP_METHOD")

        patchStatusCollector.record(
            patchId = PatchId.BOOTSTRAP,
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "BootstrapInjectedAfterProcessGuard",
        )
    }
}

/** 設定を読み込めない状態で機能 hook を入れないため、bootstrap の失敗は必須失敗として扱います。 */
private fun bootstrapFailure(reason: String, actualTargetCount: Int): Nothing {
    patchStatusCollector.record(
        patchId = PatchId.BOOTSTRAP,
        expectedTargetCount = 1,
        actualTargetCount = actualTargetCount,
        reason = reason,
    )
    throw PatchException("Linimal bootstrap target is not uniquely matched.")
}
