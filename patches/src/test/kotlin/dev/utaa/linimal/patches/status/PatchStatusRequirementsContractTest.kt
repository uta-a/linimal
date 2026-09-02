package dev.utaa.linimal.patches.status

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * <p>feature ごと requirements に未登録の場合、extension は完全適用を確認できないものとして
 * その feature を利用不可にします。登録漏れを build-time で見つけられるよう、[PatchId] が参照する
 * feature のうち runtime で設定可能なものがすべて登録されていることもここで検証します。</p>
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

    /**
     * runtime で設定可能な feature が requirements へ登録されていることを検証します。
     * 上のテストは requirements に載っている feature しか見ないため、feature ごと登録し忘れた場合を
     * ここで拾います。未登録の feature は extension 側で利用不可に倒れます。
     */
    @Test
    fun `every configurable feature that patches report into is registered`() {
        val declared = declaredRequirements()
        val configurable = configurableFeatureIds()
        val reported = PatchId.entries.map { it.featureId.value }.toSet()

        val unregistered = reported.intersect(configurable) - declared.keys

        assertEquals(
            emptySet(),
            unregistered,
            "設定可能な feature が PatchStatusRequirements に登録されていません: $unregistered。" +
                "未登録の feature は、patch がすべて適用されていても利用不可になります。",
        )
    }

    /**
     * 正規表現の取りこぼしを件数で検出します。個々の突き合わせは、拾えなかった行をそのまま
     * 見逃すため、ソース中の素朴な出現回数を期待値として明示します。
     */
    @Test
    fun `the requirement parser reads every entry in the source`() {
        val source = requirementsSource().readText()
        val declared = declaredRequirements()

        assertEquals(
            PUT_CALL.findAll(source).count(),
            declared.size,
            "requirements.put の呼び出し数と読み取れた feature 数が一致しません。",
        )
        assertEquals(
            PATCH_ID_LITERAL.findAll(source).count(),
            declared.values.sumOf { it.size },
            "ソース中の patch ID リテラル数と読み取れた patch ID 数が一致しません。",
        )
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

    /**
     * runtime で設定可能な feature ID を返します。
     *
     * <p>判定は `LinimalFeature` の enum 定数と `ReadReceiptMode.FEATURE_ID` を基準にします。
     * `FeatureCatalog` は表示対象の一覧に過ぎず、`LinimalConfig` はこの 2 つを通してのみ feature を
     * 読み書きするためです。</p>
     *
     * <p>限界: extension 側の型を参照できないため、requirements と同じくソースを正規表現で読みます。
     * そのため (1) enum 定数の書式が変わると取りこぼす、(2) 上記 2 か所以外の経路で設定可能にした
     * feature は検出できない、(3) ファイルを移動・改名すると失敗する、という制約があります。
     * (1) は定数ごとの literal 数と突き合わせて検出し、(3) はファイルが見つからない時点で失敗します。
     * (2) は検出できないため、設定可能な feature は `LinimalFeature` へ足す運用を前提とします。</p>
     */
    private fun configurableFeatureIds(): Set<String> {
        val featureSource = extensionSource(LINIMAL_FEATURE_PATH).readText()
        val constants = FEATURE_CONSTANT.findAll(featureSource).map { it.groupValues[1] }.toList()
        assertEquals(
            FEATURE_ID_LITERAL.findAll(featureSource).count(),
            constants.size,
            "LinimalFeature の feature ID リテラル数と読み取れた定数の数が一致しません。",
        )
        assertTrue(constants.isNotEmpty(), "LinimalFeature から feature ID を読み取れませんでした。")

        val readReceiptSource = extensionSource(READ_RECEIPT_MODE_PATH).readText()
        val readReceiptFeatureId = assertNotNull(
            READ_RECEIPT_FEATURE_ID.find(readReceiptSource)?.groupValues?.get(1),
            "ReadReceiptMode から FEATURE_ID を読み取れませんでした。",
        )

        return constants.toSet() + readReceiptFeatureId
    }

    private fun requirementsSource(): File = extensionSource(REQUIREMENTS_PATH)

    private fun extensionSource(relative: String): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) {
                return candidate
            }
            directory = directory.parentFile
        }
        error("$relative が見つかりませんでした。")
    }

    private companion object {
        const val REQUIREMENTS_PATH = "extensions/linimal/src/main/java/dev/utaa/linimal/extension/" +
            "status/PatchStatusRequirements.java"
        const val LINIMAL_FEATURE_PATH = "extensions/linimal/src/main/java/dev/utaa/linimal/extension/" +
            "config/LinimalFeature.java"
        const val READ_RECEIPT_MODE_PATH = "extensions/linimal/src/main/java/dev/utaa/linimal/extension/" +
            "config/ReadReceiptMode.java"

        val REQUIREMENT_ENTRY =
            Regex("""requirements\.put\(\s*"([^"]+)"\s*,\s*ids\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
        val QUOTED = Regex("\"([^\"]+)\"")
        val PUT_CALL = Regex("""requirements\.put\(""")
        val PATCH_ID_LITERAL = Regex("\"linimal\\.patch\\.[^\"]+\"")

        /** `NAME("linimal.x"),` という enum 定数の書式だけを読み取ります。 */
        val FEATURE_CONSTANT =
            Regex("""^\s*[A-Z][A-Z0-9_]*\(\s*"(linimal\.[^"]+)"\s*\)""", RegexOption.MULTILINE)
        val FEATURE_ID_LITERAL = Regex("\"linimal\\.[^\"]+\"")
        val READ_RECEIPT_FEATURE_ID = Regex("""FEATURE_ID\s*=\s*"(linimal\.[^"]+)"""")
    }
}
