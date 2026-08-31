package dev.utaa.linimal.patches.status

import app.morphe.patcher.patch.rawResourcePatch
import dev.utaa.linimal.patches.shared.Constants

/**
 * 最終的な build-time status report を raw APK asset として書き込みます。raw-resource モードは意図的なものです。
 * このパッチは対象アプリのコンパイル済み Android resources を変更しません。
 */
val patchStatusResourcePatch = rawResourcePatch {
    execute {
        patchStatusCollector.reset()
        patchStatusCollector.record(
            patchId = PatchId.PATCH_STATUS_RESOURCE,
            expectedTargetCount = 0,
            actualTargetCount = 0,
            reason = "Status report resource prepared.",
        )
    }

    finalize {
        val destination = get(Constants.PATCH_STATUS_ASSET_PATH, copy = false)
        destination.parentFile?.mkdirs()
        destination.writeText(patchStatusCollector.toJson())
    }
}
