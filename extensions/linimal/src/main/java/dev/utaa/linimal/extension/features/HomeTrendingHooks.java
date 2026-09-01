package dev.utaa.linimal.extension.features;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Home26 の話題 module の child composable 呼び出しだけを抑制します。 */
public final class HomeTrendingHooks {
    private HomeTrendingHooks() {
    }

    /** config/runtime の異常時は元の compose invoke を実行します。 */
    public static boolean shouldSuppress() {
        try {
            return LinimalConfig.get().isHomeTrendingSuppressionEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
