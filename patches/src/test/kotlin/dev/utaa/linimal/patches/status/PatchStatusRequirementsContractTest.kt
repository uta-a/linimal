package dev.utaa.linimal.patches.status

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * patch 側の [PatchId] と extension 側の `PatchStatusRequirements` の契約テストです。
 *
 * <p>extension は、feature に必要な patch ID の集合が「完全に一致」する場合だけその feature を
 * 利用可能にします。そのため、[PatchId] へ新しい patch を足して既存の [FeatureId] へ結び付けたのに
 * requirements 側へ追加し忘れると、集合が一致せず **その feature 全体が無効化**されます。
 * 実際にこの取りこぼしで「特集枠を表示しない」と、それに連動する読み込み表示の抑制が
 * 同時に効かなくなりました。</p>
 *
 * <p>両者は別 Gradle module にあり型として共有できないため、requirements の Java ソースを
 * 読んで文字列として突き合わせます。</p>
 */
class PatchStatusRequirementsContractTest {
    @Test
    fun `every configurable feature lists exactly the patches that report into it`() {
        val declared = declaredRequirements()
        assertTrue(declared.isNotEmpty(), "PatchStatusRequirements から feature を読み取れませんでした。")

        val actual = PatchId.entries
            .filter { it.featureId.value in declared.keys }
            .groupBy({ it.featureId.value }, { it.value })
            .mapValues { (_, ids) -> ids.toSet() }

        for ((featureId, requiredIds) in declared) {
            assertEquals(
                actual[featureId].orEmpty(),
                requiredIds,
                "feature '$featureId' の必要 patch ID が PatchId の実態と一致しません。" +
                    "PatchStatusRequirements への追加漏れがあると、その feature 全体が無効になります。",
            )
        }
    }

    /** `requirements.put("<feature>", ids("<patch>", ...));` を読み取ります。 */
    private fun declaredRequirements(): Map<String, Set<String>> {
        val source = requirementsSource().readText()
        return REQUIREMENT_ENTRY.findAll(source).associate { match ->
            val featureId = match.groupValues[1]
            val patchIds = QUOTED.findAll(match.groupValues[2]).map { it.groupValues[1] }.toSet()
            featureId to patchIds
        }
    }

    private fun requirementsSource(): File {
        val relative = "extensions/linimal/src/main/java/dev/utaa/linimal/extension/status/" +
            "PatchStatusRequirements.java"
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        error("PatchStatusRequirements.java が見つかりませんでした。")
    }

    private companion object {
        val REQUIREMENT_ENTRY =
            Regex("""requirements\.put\(\s*"([^"]+)"\s*,\s*ids\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
        val QUOTED = Regex("\"([^\"]+)\"")
    }
}
