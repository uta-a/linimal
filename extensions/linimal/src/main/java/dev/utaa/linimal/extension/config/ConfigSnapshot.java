package dev.utaa.linimal.extension.config;

import java.util.EnumMap;

/** hook が使用する不変で検証済みの設定。 */
final class ConfigSnapshot {
    private final EnumMap<LinimalFeature, Boolean> featureStates;
    private final ReadReceiptMode readReceiptMode;
    private final boolean keepUnreadAfterSend;

    ConfigSnapshot(
            EnumMap<LinimalFeature, Boolean> featureStates,
            ReadReceiptMode readReceiptMode,
            boolean keepUnreadAfterSend) {
        this.featureStates = new EnumMap<>(featureStates);
        this.readReceiptMode = readReceiptMode;
        this.keepUnreadAfterSend = keepUnreadAfterSend;
    }

    static ConfigSnapshot originalBehavior() {
        EnumMap<LinimalFeature, Boolean> featureStates = new EnumMap<>(LinimalFeature.class);
        for (LinimalFeature feature : LinimalFeature.values()) {
            featureStates.put(feature, false);
        }
        return new ConfigSnapshot(featureStates, ReadReceiptMode.NORMAL, false);
    }

    boolean isEnabled(LinimalFeature feature) {
        return Boolean.TRUE.equals(featureStates.get(feature));
    }

    ReadReceiptMode readReceiptMode() {
        return readReceiptMode;
    }

    boolean keepUnreadAfterSend() {
        return keepUnreadAfterSend;
    }
}
