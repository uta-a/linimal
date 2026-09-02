package dev.utaa.linimal.extension.features;

import java.util.ArrayList;
import java.util.List;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** 下部ナビゲーションに渡すタブ列を、表示設定に応じて安全に絞り込みます。 */
public final class MainTabHooks {
    private MainTabHooks() {
    }

    /**
     * LINE の enum 型を extension 側へリンクせず、名前だけで対象タブを判定します。
     * すべて OFF の場合は必ず元の List instance を返します。
     */
    public static List<?> filterTabs(List<?> original) {
        if (original == null) {
            return null;
        }
        try {
            LinimalConfig config = LinimalConfig.get();
            return filterTabsForEnabledStates(
                    original,
                    config.isVoomSuppressionEnabled(),
                    config.isNewsSuppressionEnabled(),
                    config.isWalletSuppressionEnabled(),
                    config.isShoppingSuppressionEnabled(),
                    config.isMiniSuppressionEnabled());
        } catch (Throwable ignored) {
            return original;
        }
    }

    static List<?> filterTabsForEnabledStates(
            List<?> original,
            boolean suppressVoom,
            boolean suppressNews,
            boolean suppressWallet,
            boolean suppressShopping,
            boolean suppressMini) {
        if (original == null
                || (!suppressVoom && !suppressNews && !suppressWallet && !suppressShopping && !suppressMini)) {
            return original;
        }

        ArrayList<Object> filtered = new ArrayList<>(original.size());
        for (Object tab : original) {
            String name = enumName(tab);
            if ((suppressVoom && "TIMELINE".equals(name))
                    || (suppressNews && ("NEWS".equals(name) || "NEWS_ROW".equals(name)))
                    || (suppressWallet && "WALLET".equals(name))
                    || (suppressShopping && ("COMMERCE".equals(name) || "COMMERCE_TW".equals(name)))
                    || (suppressMini && "MINI".equals(name))) {
                continue;
            }
            filtered.add(tab);
        }
        return filtered;
    }

    private static String enumName(Object value) {
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : null;
    }
}
