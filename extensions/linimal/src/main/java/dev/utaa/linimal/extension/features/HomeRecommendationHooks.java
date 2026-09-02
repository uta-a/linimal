package dev.utaa.linimal.extension.features;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Home Contents Recommendation の renderer が presentation を行うかを判定します。 */
public final class HomeRecommendationHooks {
    private HomeRecommendationHooks() {
    }

    /** config/runtime の異常時は renderer の元の反復処理を続行します。 */
    public static boolean shouldSuppress() {
        try {
            return LinimalConfig.get().isHomeRecommendationsSuppressionEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
