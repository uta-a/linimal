package dev.utaa.linimal.patches.core

import app.morphe.patcher.patch.bytecodePatch
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.patchStatusCollector

/** Linimal runtime extension の DEX を対象 APK に統合します。 */
val linimalExtensionMergePatch = bytecodePatch {
    dependsOn(linimalManifestComponentRegistrationPatch)
    extendWith("extensions/linimal.mpe")

    execute {
        patchStatusCollector.record(
            patchId = PatchId.EXTENSION_MERGE,
            expectedTargetCount = 0,
            actualTargetCount = 0,
            reason = "ExtensionMergeCompleted",
        )
    }
}
