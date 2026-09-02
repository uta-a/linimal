package dev.utaa.linimal.extension.features;

import dev.utaa.linimal.extension.config.LinimalConfig;

/**
 * ホームのフィードが 1 件も描かれないときに残る、消えない読み込み表示を Compose 前に抑制するための
 * runtime gate です。
 *
 * <p>この読み込み表示は Home の推薦・トレンド・投稿カード・特集枠の 4 設定がすべて ON のときにだけ
 * 残ります。新しい設定項目は増やさず、既存の 4 つの accessor がすべて true のときだけ抑制します。</p>
 */
public final class HomeFeedLoadingIndicatorHooks {
    private HomeFeedLoadingIndicatorHooks() {
    }

    /**
     * Home の推薦・トレンド・投稿カード・特集枠の抑制設定がすべて ON の場合だけ、読み込み表示の
     * renderer を抑制します。1 つでも OFF、未初期化、または設定読み取り時の例外では false を返し、
     * LINE の元の renderer を実行します。
     */
    public static boolean shouldSuppress() {
        return HomeSuppressionGate.shouldSuppress(new HomeSuppressionGate.SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                LinimalConfig config = LinimalConfig.get();
                return config.isHomeRecommendationsSuppressionEnabled()
                        && config.isHomeTrendingSuppressionEnabled()
                        && config.isHomeFeedPostCardsSuppressionEnabled()
                        && config.isHomeFeaturedCollectionsSuppressionEnabled();
            }
        });
    }
}
