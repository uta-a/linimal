package dev.utaa.linimal.extension.config;

/** Linimal が LINE の通常の read-receipt 動作を変更するかどうかを制御します。 */
public enum ReadReceiptMode {
    /** LINE の元の read-receipt 動作を維持します。 */
    NORMAL("normal"),

    /** Linimal の hook が利用可能な場合に、手動の read-receipt 動作を有効にします。 */
    MANUAL("manual");

    private final String storedValue;

    ReadReceiptMode(String storedValue) {
        this.storedValue = storedValue;
    }

    static ReadReceiptMode fromStoredValue(String storedValue) {
        for (ReadReceiptMode mode : values()) {
            if (mode.storedValue.equals(storedValue)) {
                return mode;
            }
        }
        throw new ConfigStoreException("Invalid read receipt mode");
    }

    String storedValue() {
        return storedValue;
    }
}
