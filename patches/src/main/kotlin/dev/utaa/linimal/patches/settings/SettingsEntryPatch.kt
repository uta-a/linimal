package dev.utaa.linimal.patches.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.patchStatusCollector

private const val LIST_TYPE = "Ljava/util/List;"
private const val OBJECT_ARRAY = "[Ljava/lang/Object;"

/**
 * 難読化された object 型を受けるワイルドカードです。Morphe の parameter 照合は前方一致のため、
 * `"L"` は任意の object 型に一致します。版ごとに変わる名前を条件に持ち込まないための手段で、
 * MainTabsPatch の constructor fingerprint と同じ手法です。
 */
private const val ANY_OBJECT = "L"
private const val APPEND_ENTRY_METHOD =
    "Ldev/utaa/linimal/extension/settings/SettingsEntryHooks;->appendEntry(Ljava/util/List;)Ljava/util/List;"

/**
 * 設定一覧を組み立てる static initializer を、難読化名ではなく初期化内に残る
 * 関数参照名と、リスト生成呼び出しの形状で特定します。
 *
 * <p>26.11.0 では `createLypItemDescription` と `isPremiumSubscribed` も条件に含め、
 * superclass と accessFlags も見ていました。26.14.0 では `createLypItemDescription` が
 * APK 全体から消え、`isPremiumSubscribed` はこの `<clinit>` には現れず、superclass の
 * 難読化名も変わりました（`Lpx4/p1;` → `Lm55/q1;`）。残る 2 つの関数参照名だけで全 DEX の
 * `<clinit>` を走査しても一致は 1 件のため、版ごとに変わる条件は落としています。
 * `name = "<clinit>"` が static/constructor を含意するので accessFlags も不要です。</p>
 *
 * <p>リスト生成も `Leb8/r;->E` という難読化名での指定をやめ、
 * 「`[Ljava/lang/Object;` を受けて `List` を返す static 呼び出し」という形状で当てます。
 * 26.14.0 では `Lji8/r;->F` に変わっていますが、形状は同じです。</p>
 */
internal val mainSettingsListFingerprint = Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        string("shouldShowCustomAppIconSettings"),
        string("openIapPurchaseHistory"),
        methodCall(
            parameters = listOf(OBJECT_ARRAY),
            returnType = LIST_TYPE,
            opcode = Opcode.INVOKE_STATIC,
        ),
    ),
)

/**
 * 設定項目モデルの constructor を、名前ではなく引数の形状だけで特定します。
 * runtime 側の [SettingsEntryFactory] も同じ 13 引数の形状を前提に reflection するため、
 * ここで一意性を検証します。
 *
 * <p>難読化された型（Kotlin の Function1 / Function2、enum、モデル種別）は [ANY_OBJECT] で
 * 受けます。26.14.0 では 26.11.0 から名前だけが変わり、形状は同一でした
 * （`Lvb8/p;` → `Laj8/p;`、`Lu08/e;` → `Lb88/e;`、`Lvb8/l;` → `Laj8/l;`、
 * `Lpx4/t0;` → `Lm55/u0;`）。非難読化の 0 / 1 / 2 / 5 / 12 番目だけで全 DEX を走査しても
 * 一致は 1 件のため、名前は条件に含めません。accessFlags も同じ理由で落としています。</p>
 */
internal val settingsItemConstructorFingerprint = Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/Integer;",
        "I",
        ANY_OBJECT,
        ANY_OBJECT,
        "Ljava/lang/Integer;",
        ANY_OBJECT,
        ANY_OBJECT,
        ANY_OBJECT,
        ANY_OBJECT,
        ANY_OBJECT,
        ANY_OBJECT,
        "Z",
    ),
)

/** LINE の既存項目を差し替えず、Linimal の設定項目を 1 件だけ追加します。 */
val settingsEntryPatch = bytecodePatch(
    name = "Linimal 設定の追加",
    description = "LINE の設定画面へ Linimal の設定項目を追加します。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(linimalSettingsResourcePatch)

    execute {
        val listMatches = mainSettingsListFingerprint.matchAllOrNull().orEmpty()
        val constructorMatches = settingsItemConstructorFingerprint.matchAllOrNull().orEmpty()
        val actualTargetCount = listMatches.size + constructorMatches.size
        if (listMatches.size != 1 || constructorMatches.size != 1) {
            settingsFailure(
                "SettingsFingerprintMismatch",
                actualTargetCount,
                "list=${listMatches.size}, constructor=${constructorMatches.size}",
            )
        }

        val match = listMatches.single()
        val method = match.method
        val listBuilderIndex = match.instructionMatches[2].index
        val moveResult = method.getInstruction<OneRegisterInstruction>(listBuilderIndex + 1)
        val storeField = method.getInstruction<ReferenceInstruction>(listBuilderIndex + 2)
        val listField = storeField.reference as? FieldReference
        val resultRegister = moveResult.registerA

        // 生成したリストを static field へ保存する直前の 3 命令が想定どおりの場合だけ変更します。
        if (
            moveResult.opcode != Opcode.MOVE_RESULT_OBJECT ||
            storeField.opcode != Opcode.SPUT_OBJECT ||
            listField?.type != LIST_TYPE ||
            listField.definingClass != match.originalClassDef.type ||
            resultRegister > 15
        ) {
            settingsFailure("SettingsListInstructionShapeMismatch", actualTargetCount, "list tail")
        }

        // 元のリストは変更せず、追加済みのリストで同じ register を置き換えます。
        // static field への保存自体は LINE 側の命令のまま残ります。
        method.addInstructions(
            listBuilderIndex + 2,
            """
                invoke-static { v$resultRegister }, $APPEND_ENTRY_METHOD
                move-result-object v$resultRegister
            """.trimIndent(),
        )

        patchStatusCollector.record(
            patchId = PatchId.SETTINGS_ENTRY,
            expectedTargetCount = 2,
            actualTargetCount = 2,
            reason = "SettingsEntryAppended",
        )
    }
}

/** 設定画面へ到達できない build を出荷しないため、設定入口の失敗は必須失敗として扱います。 */
private fun settingsFailure(reason: String, actualTargetCount: Int, detail: String): Nothing {
    patchStatusCollector.record(
        patchId = PatchId.SETTINGS_ENTRY,
        expectedTargetCount = 2,
        actualTargetCount = actualTargetCount,
        reason = reason,
    )
    throw PatchException("Linimal Settings entry target is not uniquely matched ($detail).")
}
