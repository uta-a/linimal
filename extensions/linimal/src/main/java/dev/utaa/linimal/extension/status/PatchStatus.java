package dev.utaa.linimal.extension.status;

/** build-time に記録された patch の適用状態。 */
public enum PatchStatus {
    OK,
    PARTIAL,
    TARGET_NOT_FOUND,
    DISABLED,
    ERROR
}
