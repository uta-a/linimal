package dev.utaa.linimal.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

object Constants {
    const val PATCH_STATUS_ASSET_PATH = "assets/linimal/patch-status.json"
    const val PATCH_STATUS_SCHEMA_VERSION = 1

    val LINE_COMPATIBILITY = Compatibility(
        name = "LINE",
        packageName = "jp.naver.line.android",
        apkFileType = ApkFileType.APKM_REQUIRED,
        targets = listOf(
            AppTarget(
                version = "26.11.0",
                versionCodes = mapOf(SupportedAbi.ARM64_V8A to 261100124),
            ),
        ),
    )
}
