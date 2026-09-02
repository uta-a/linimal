package dev.utaa.linimal.extension.config;

/**
 * build-time の patch 適用状況から、機能ごとに Linimal の変更を適用してよいかを判定する境界。
 *
 * <p>設定の読み取りは必ずこの判定を通します。判定できない場合は利用不可を返し、hook は LINE の
 * 元の動作を維持します。</p>
 */
interface FeatureAvailability {
    /** patch status を読めなかった場合の判定です。すべての機能を利用不可として扱います。 */
    FeatureAvailability NONE = featureId -> false;

    /** patch status を伴わずに保存値だけを検証するための test 境界です。 */
    FeatureAvailability ALL = featureId -> true;

    /** 指定した feature ID の必須 patch がすべて適用済みの場合だけ true を返します。 */
    boolean isAvailable(String featureId);
}
