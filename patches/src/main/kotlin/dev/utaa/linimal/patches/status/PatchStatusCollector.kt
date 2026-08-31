package dev.utaa.linimal.patches.status

import dev.utaa.linimal.patches.shared.Constants

/**
 * build-time hook メタデータだけを収集します。意図的に APK、DEX、runtime の入力を持たないため、
 * JSON レポートに対象アプリの content が誤って含まれることはありません。
 */
class PatchStatusCollector {
    private val records = linkedMapOf<PatchId, PatchStatusRecord>()

    fun reset() {
        records.clear()
    }

    fun record(
        patchId: PatchId,
        expectedTargetCount: Int,
        actualTargetCount: Int,
        reason: String? = null,
    ): PatchStatusRecord = record(
        PatchStatusRecord(
            patchId = patchId,
            status = statusFor(expectedTargetCount, actualTargetCount),
            expectedTargetCount = expectedTargetCount,
            actualTargetCount = actualTargetCount,
            reason = sanitizeReason(reason),
        ),
    )

    fun record(record: PatchStatusRecord): PatchStatusRecord {
        val sanitized = record.copy(reason = sanitizeReason(record.reason))
        records[sanitized.patchId] = sanitized
        return sanitized
    }

    fun snapshot(): List<PatchStatusRecord> = records.values.sortedBy { it.patchId.value }

    fun toJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ")
        append(Constants.PATCH_STATUS_SCHEMA_VERSION)
        append(",\n  \"patches\": [")

        snapshot().forEachIndexed { index, record ->
            if (index > 0) append(',')
            append("\n    {")
            append("\"patchId\": \"")
            append(jsonString(record.patchId.value))
            append("\", \"featureId\": \"")
            append(jsonString(record.featureId.value))
            append("\", \"status\": \"")
            append(record.status.name)
            append("\", \"expectedTargetCount\": ")
            append(record.expectedTargetCount)
            append(", \"actualTargetCount\": ")
            append(record.actualTargetCount)
            record.reason?.let { reason ->
                append(", \"reason\": \"")
                append(jsonString(reason))
                append('"')
            }
            append('}')
        }

        if (records.isNotEmpty()) append('\n').append("  ")
        append("]\n}\n")
    }

    companion object {
        /**
         * 一意な expected target は完全一致しなければなりません。optional target set は expected
         * count に 1 より大きい値を指定でき、target の一部だけが存在する場合は partial になります。
         */
        fun statusFor(expectedTargetCount: Int, actualTargetCount: Int): PatchStatus {
            require(expectedTargetCount >= 0) { "expectedTargetCount must not be negative" }
            require(actualTargetCount >= 0) { "actualTargetCount must not be negative" }

            return when {
                actualTargetCount > expectedTargetCount -> PatchStatus.ERROR
                actualTargetCount == expectedTargetCount -> PatchStatus.OK
                actualTargetCount == 0 -> PatchStatus.TARGET_NOT_FOUND
                else -> PatchStatus.PARTIAL
            }
        }

        /**
         * patch report は URL、credential、message content、任意の target detail を公開してはいけません。
         * reason は短い 1 行の diagnostic label に制限します。
         */
        fun sanitizeReason(reason: String?): String? {
            val normalized = reason
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            if (
                normalized.length > 160 ||
                SENSITIVE_REASON_PATTERN.containsMatchIn(normalized) ||
                !SAFE_REASON_PATTERN.matches(normalized)
            ) {
                return "Details omitted."
            }

            return normalized
        }

        private fun jsonString(value: String): String = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '\"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (character.code < 0x20) {
                            append("\\u%04x".format(character.code))
                        } else {
                            append(character)
                        }
                    }
                }
            }
        }

        private val SAFE_REASON_PATTERN = Regex("[A-Za-z0-9 ._()/-]+")
        private val SENSITIVE_REASON_PATTERN = Regex(
            "(?i)(https?://|www\\.|token|cookie|authorization|bearer|message|content|[?&][A-Za-z0-9_.-]+=|(?:[A-Za-z_\$][A-Za-z0-9_\$]*\\.){2,}[A-Za-z_\$][A-Za-z0-9_\$]*)",
        )
    }
}

val patchStatusCollector = PatchStatusCollector()
