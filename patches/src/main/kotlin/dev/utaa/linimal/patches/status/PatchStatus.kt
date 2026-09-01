package dev.utaa.linimal.patches.status

/** 埋め込み patch-status レポートに書き込む安定した識別子。 */
enum class FeatureId(val value: String) {
    STATUS("linimal.status"),
    COMPONENT_REGISTRATION("linimal.component-registration"),
    EXTENSION("linimal.extension"),
    BOOTSTRAP("linimal.bootstrap"),
    SETTINGS("linimal.settings"),
    PREMIUM("linimal.premium"),
    PROBE("linimal.probe"),
    LINIMAL("linimal.core"),
}

enum class PatchId(val value: String, val featureId: FeatureId) {
    PATCH_STATUS_RESOURCE("linimal.patch.status-resource", FeatureId.STATUS),
    COMPONENT_REGISTRATION("linimal.patch.component-registration", FeatureId.COMPONENT_REGISTRATION),
    EXTENSION_MERGE("linimal.patch.extension-merge", FeatureId.EXTENSION),
    BOOTSTRAP("linimal.patch.bootstrap", FeatureId.BOOTSTRAP),
    SETTINGS_RESOURCE("linimal.patch.settings-resource", FeatureId.SETTINGS),
    SETTINGS_ENTRY("linimal.patch.settings-entry", FeatureId.SETTINGS),
    PREMIUM_UNSEND("linimal.patch.premium-unsend", FeatureId.PREMIUM),
    NO_OP_PROBE("linimal.patch.no-op-probe", FeatureId.PROBE),
    LINIMAL("linimal.patch.linimal", FeatureId.LINIMAL),
}

enum class PatchStatus {
    OK,
    PARTIAL,
    TARGET_NOT_FOUND,
    DISABLED,
    ERROR,
}

/**
 * 内部パッチ 1 件の build-time 結果。カウントは、そのパッチが意図的に検索した対象だけを示します。
 * そのため no-op パッチでは expected と actual の対象数がともに 0 になります。
 */
data class PatchStatusRecord(
    val patchId: PatchId,
    val featureId: FeatureId = patchId.featureId,
    val status: PatchStatus,
    val expectedTargetCount: Int,
    val actualTargetCount: Int,
    val reason: String? = null,
) {
    init {
        require(featureId == patchId.featureId) { "featureId must match patchId" }
        require(expectedTargetCount >= 0) { "expectedTargetCount must not be negative" }
        require(actualTargetCount >= 0) { "actualTargetCount must not be negative" }
    }
}
