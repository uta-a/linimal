package dev.utaa.linimal.extension.features;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** ホームの特集枠（ショートフォームのグリッド module）を Compose 前に抑制するための runtime gate です。 */
public final class HomeFeaturedCollectionsHooks {
    interface SuppressionState {
        boolean isSuppressionEnabled() throws Throwable;
    }

    private HomeFeaturedCollectionsHooks() {
    }

    /**
     * ホームの特集枠設定が ON の場合だけ特集枠の renderer を抑制します。
     * 設定 OFF、未初期化、または設定読み取り時の例外では false を返し、LINE の元の renderer を実行します。
     */
    public static boolean shouldSuppress() {
        return shouldSuppressWith(new SuppressionState() {
            @Override
            public boolean isSuppressionEnabled() {
                return LinimalConfig.get().isHomeFeaturedCollectionsSuppressionEnabled();
            }
        });
    }

    static boolean shouldSuppressWith(SuppressionState state) {
        if (state == null) {
            return false;
        }
        try {
            return state.isSuppressionEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
