package dev.utaa.linimal.extension.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.utaa.linimal.extension.config.LinimalConfig;

/** Home Performance Ad 専用 Flow が adapter へ渡す item list を runtime 設定で制御します。 */
public final class HomeTopAdHooks {
    private static final int HOME_DEFAULT_MODULE_COUNT = 7;
    private static final int MIDDLE_PERFORMANCE_AD_INDEX = 4;
    private static final int BOTTOM_PERFORMANCE_AD_INDEX = 6;
    private static final String PERFORMANCE_AD_NAME = "home_performance_ad";
    private static final String MIDDLE_PERFORMANCE_AD_ID =
            "home-content-server_home-performance-ad-middle";
    private static final String BOTTOM_PERFORMANCE_AD_ID =
            "home-content-server_home-performance-ad-bottom";

    private HomeTopAdHooks() {
    }

    /**
     * 設定が ON のときだけ、専用 module が生成した performance ad item list を空にします。
     * 設定が OFF、未初期化、または読み取り失敗時には入力 list 自体を返します。
     */
    public static List<?> filterPerformanceAdItems(List<?> originalItems) {
        try {
            return filterPerformanceAdItemsForEnabledState(
                    originalItems,
                    LinimalConfig.get().isHomeTopAdSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalItems;
        }
    }

    static List<?> filterPerformanceAdItemsForEnabledState(
            List<?> originalItems,
            boolean suppressionEnabled) {
        if (!suppressionEnabled || originalItems == null) {
            return originalItems;
        }
        return Collections.emptyList();
    }

    /**
     * Home default module catalog に含まれる middle / bottom Performance Ad だけを除外します。
     * catalog の要素数、順序、各 entry の安定した toString contract が確認できない入力は変更しません。
     */
    public static List<?> filterHomePerformanceAdCatalogItems(List<?> originalItems) {
        try {
            return filterHomePerformanceAdCatalogItemsForEnabledState(
                    originalItems,
                    LinimalConfig.get().isHomeTopAdSuppressionEnabled());
        } catch (Throwable ignored) {
            return originalItems;
        }
    }

    static List<?> filterHomePerformanceAdCatalogItemsForEnabledState(
            List<?> originalItems,
            boolean suppressionEnabled) {
        if (!suppressionEnabled || originalItems == null) {
            return originalItems;
        }
        try {
            if (originalItems.size() != HOME_DEFAULT_MODULE_COUNT
                    || !isExpectedPerformanceAdModule(
                            originalItems.get(MIDDLE_PERFORMANCE_AD_INDEX), MIDDLE_PERFORMANCE_AD_ID)
                    || !isExpectedPerformanceAdModule(
                            originalItems.get(BOTTOM_PERFORMANCE_AD_INDEX), BOTTOM_PERFORMANCE_AD_ID)) {
                return originalItems;
            }

            List<Object> filtered = new ArrayList<>(HOME_DEFAULT_MODULE_COUNT - 2);
            for (int index = 0; index < originalItems.size(); index++) {
                if (index != MIDDLE_PERFORMANCE_AD_INDEX && index != BOTTOM_PERFORMANCE_AD_INDEX) {
                    filtered.add(originalItems.get(index));
                }
            }
            return filtered;
        } catch (Throwable ignored) {
            return originalItems;
        }
    }

    private static boolean isExpectedPerformanceAdModule(Object item, String moduleId) {
        if (item == null) {
            return false;
        }
        String expectedPrefix = "GcsDefaultModule(id=" + moduleId
                + ", name=" + PERFORMANCE_AD_NAME + ", payload=";
        return item.toString().startsWith(expectedPrefix);
    }
}
