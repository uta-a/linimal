package dev.utaa.linimal.extension.features;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Home 下部の Home Feed post module を Compose 前に抑制するための runtime gate です。 */
public final class HomeFeedPostCardHooks {
    private HomeFeedPostCardHooks() {
    }

    /**
     * ホームの投稿カード設定が ON の場合だけ post card renderer を抑制します。
     * 設定 OFF、未初期化、または設定読み取り時の例外では false を返し、LINE の元の renderer を実行します。
     */
    public static boolean shouldSuppress() {
        return HomeSuppressionGate.shouldSuppress(new HomeSuppressionGate.SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                return LinimalConfig.get().isHomeFeedPostCardsSuppressionEnabled();
            }
        });
    }
}
