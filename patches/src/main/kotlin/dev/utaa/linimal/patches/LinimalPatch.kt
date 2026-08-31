package dev.utaa.linimal.patches

import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import dev.utaa.linimal.patches.core.noOpProbePatch
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.patchStatusCollector

/** ユーザーが選択できる唯一のパッチ。機能はパッチ選択ではなく、実行時設定で制御します。 */
@Suppress("unused")
val linimalPatch = bytecodePatch(
    name = "Linimal",
    description = "LINE向けに、実行時設定に対応したLinimalのパッチ基盤を導入します。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.ENABLED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(noOpProbePatch)

    execute {
        patchStatusCollector.record(
            patchId = PatchId.LINIMAL,
            expectedTargetCount = 0,
            actualTargetCount = 0,
            reason = "Linimal foundation installed.",
        )
    }
}
