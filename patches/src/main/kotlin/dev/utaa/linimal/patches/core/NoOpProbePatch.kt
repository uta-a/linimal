package dev.utaa.linimal.patches.core

import app.morphe.patcher.patch.bytecodePatch
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.patchStatusCollector
import dev.utaa.linimal.patches.status.patchStatusResourcePatch

/**
 * bytecode patch pipeline が、対象コードを選択・変更せずに実行されたことを確認します。
 * fingerprint は意図的に宣言せず、命令も出力しません。
 */
val noOpProbePatch = bytecodePatch {
    // この依存関係により、Set の反復順にかかわらず probe より先に reset が実行されます。
    dependsOn(patchStatusResourcePatch)

    execute {
        patchStatusCollector.record(
            patchId = PatchId.NO_OP_PROBE,
            expectedTargetCount = 0,
            actualTargetCount = 0,
            reason = "No bytecode targets intentionally selected.",
        )
    }
}
