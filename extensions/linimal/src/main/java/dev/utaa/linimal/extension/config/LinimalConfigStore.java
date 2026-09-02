package dev.utaa.linimal.extension.config;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 型付き Linimal 設定と persistent storage の間にある唯一の境界。
 *
 * <p>このクラスがすべての {@link SharedPreferences} access を意図的に所有することで、将来
 * process-coherent な実装に置き換える際にも、永続化の詳細を hook に公開せずに済みます。</p>
 */
public final class LinimalConfigStore {
    private static final String PREFERENCES_NAME = "linimal.config";

    private final PreferenceBackend backend;

    private LinimalConfigStore(PreferenceBackend backend) {
        this.backend = backend;
    }

    static LinimalConfigStore open(Context context) {
        if (context == null) {
            throw new ConfigStoreException("Configuration context is unavailable");
        }

        Context applicationContext = context.getApplicationContext();
        Context storageContext = applicationContext != null ? applicationContext : context;
        try {
            return new LinimalConfigStore(
                    new SharedPreferencesBackend(
                            storageContext.getSharedPreferences(
                                    PREFERENCES_NAME,
                                    Context.MODE_PRIVATE)));
        } catch (RuntimeException exception) {
            throw new ConfigStoreException("Unable to open configuration storage", exception);
        }
    }

    static LinimalConfigStore forTesting(PreferenceBackend backend) {
        return new LinimalConfigStore(backend);
    }

    synchronized ConfigSnapshot readSnapshot() {
        Map<String, ?> values = readAll();
        ensureCurrentSchema(values);
        values = readAll();

        EnumMap<LinimalFeature, Boolean> featureStates = new EnumMap<>(LinimalFeature.class);
        for (LinimalFeature feature : LinimalFeature.values()) {
            featureStates.put(feature, readBoolean(values, feature));
        }
        ReadReceiptMode readReceiptMode = readReadReceiptMode(values);
        return new ConfigSnapshot(featureStates, readReceiptMode);
    }

    synchronized void writeFeature(LinimalFeature feature, boolean enabled) {
        ensureCurrentSchema(readAll());
        Map<String, Object> updates = new HashMap<>();
        updates.put(LinimalConfigSchema.keyFor(feature), enabled);
        write(updates);
    }

    synchronized void writeReadReceiptMode(ReadReceiptMode mode) {
        if (mode == null) {
            throw new ConfigStoreException("Read receipt mode is unavailable");
        }
        ensureCurrentSchema(readAll());
        Map<String, Object> updates = new HashMap<>();
        updates.put(LinimalConfigSchema.READ_RECEIPT_MODE_KEY, mode.storedValue());
        write(updates);
    }

    /**
     * 各 migration の commit 後に storage を再読込します。backend が process 間で変化しても、
     * 次に進める version は実際に保存された marker だけから決まります。
     */
    private void ensureCurrentSchema(Map<String, ?> values) {
        if (isFreshInstall(values)) {
            initializeFreshInstall();
            values = readAll();
        }
        int version = readSchemaVersion(values);
        while (version < LinimalConfigSchema.CURRENT_VERSION) {
            int expectedNextVersion = version + 1;
            switch (version) {
                case 0:
                    migrateV0ToV1(values);
                    break;
                case 1:
                    migrateV1ToV2(values);
                    break;
                case 2:
                    migrateV2ToV3(values);
                    break;
                default:
                    throw new ConfigStoreException("Unsupported configuration schema version");
            }
            values = readAll();
            version = readSchemaVersion(values);
            if (version != expectedNextVersion) {
                throw new ConfigStoreException("Configuration schema migration made no valid progress");
            }
        }
        if (version != LinimalConfigSchema.CURRENT_VERSION) {
            throw new ConfigStoreException("Unsupported configuration schema version");
        }
    }

    /**
     * 新規インストールかどうかを判定します。
     *
     * <p>この preferences file は Linimal だけが所有し、書き込むキーはすべて {@code linimal.}
     * 名前空間です。したがって「キーが一つも無い」ことは、この端末でまだ Linimal が一度も
     * 設定を保存していないこと、つまり新規インストールであることと同値です。</p>
     *
     * <p>schema version キーの有無では判定できません。marker を持たない v0 世代のインストールが
     * 存在し、それらは保護すべき既存の設定を持っているためです。</p>
     */
    private boolean isFreshInstall(Map<String, ?> values) {
        return values.isEmpty();
    }

    /**
     * 保存された値が無いので移行するものもありません。marker だけを現在の version にして、
     * 以後は現在の {@link LinimalDefaults} がそのままフォールバックとして効くようにします。
     */
    private void initializeFreshInstall() {
        Map<String, Object> updates = new HashMap<>();
        updates.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, LinimalConfigSchema.CURRENT_VERSION);
        write(updates);
    }

    /** v0 には schema marker がありませんでした。v1 の既存値は保持したまま marker だけを追加します。 */
    private void migrateV0ToV1(Map<String, ?> values) {
        validateMigratableValues(values);
        Map<String, Object> updates = new HashMap<>();
        updates.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, 1);
        write(updates);
    }

    /**
     * v1 の広域設定を場所別の v2 設定へ移します。
     *
     * <p>新しい場所別キーと version marker は同じ write で commit します。既に正しい型で
     * 保存されている v2 値は、v1 source より優先します。</p>
     *
     * <p>キーが無いときの値は {@link LinimalLegacyDefaults} から取ります。現在の既定値を使うと、
     * 既定値を変えるたびに移行してくる既存インストールの挙動まで変わってしまいます。</p>
     */
    private void migrateV1ToV2(Map<String, ?> values) {
        validateMigratableValues(values);

        boolean legacyAds = readLegacyBoolean(
                values,
                LinimalConfigSchema.ADS_ENABLED_KEY,
                LinimalLegacyDefaults.isEnabled(LinimalFeature.ADS));
        boolean legacyLineAi = readLegacyBoolean(
                values,
                LinimalConfigSchema.LINE_AI_ENABLED_KEY,
                LinimalLegacyDefaults.isEnabled(LinimalFeature.LINE_AI));

        Map<String, Object> updates = new HashMap<>();
        updates.put(
                LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.SMART_CHANNEL_ADS_ENABLED_KEY,
                        legacyAds));
        updates.put(
                LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.HOME_TOP_AD_ENABLED_KEY,
                        legacyAds));
        updates.put(
                LinimalConfigSchema.AGENT_I_HOME_HEADER_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.AGENT_I_HOME_HEADER_ENABLED_KEY,
                        legacyLineAi));
        updates.put(
                LinimalConfigSchema.AGENT_I_CHAT_INFORMATION_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.AGENT_I_CHAT_INFORMATION_ENABLED_KEY,
                        legacyLineAi));
        updates.put(
                LinimalConfigSchema.AGENT_I_WALLET_HEADER_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.AGENT_I_WALLET_HEADER_ENABLED_KEY,
                        legacyLineAi));
        updates.put(
                LinimalConfigSchema.AGENT_I_SETTINGS_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.AGENT_I_SETTINGS_ENABLED_KEY,
                        legacyLineAi));
        updates.put(
                LinimalConfigSchema.AGENT_I_CHAT_COMPOSER_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.AGENT_I_CHAT_COMPOSER_ENABLED_KEY,
                        legacyLineAi));
        updates.put(
                LinimalConfigSchema.AGENT_I_CHAT_LIST_SEARCH_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.AGENT_I_CHAT_LIST_SEARCH_ENABLED_KEY,
                        legacyLineAi));
        updates.put(
                LinimalConfigSchema.LINE_AI_MESSAGE_CONTEXT_MENU_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.LINE_AI_MESSAGE_CONTEXT_MENU_ENABLED_KEY,
                        legacyLineAi));
        updates.put(
                LinimalConfigSchema.LINE_AI_GALLERY_VIEWER_ENABLED_KEY,
                readNewValueOrLegacy(
                        values,
                        LinimalConfigSchema.LINE_AI_GALLERY_VIEWER_ENABLED_KEY,
                        legacyLineAi));
        updates.put(
                LinimalConfigSchema.SHOPPING_ENABLED_KEY,
                readOptionalBoolean(
                        values,
                        LinimalConfigSchema.SHOPPING_ENABLED_KEY,
                        LinimalLegacyDefaults.isEnabled(LinimalFeature.SHOPPING)));
        updates.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, 2);
        write(updates);
    }

    /**
     * 既定値の変更から既存インストールを守ります。
     *
     * <p>読み取りはキーが無いときに現在の既定値へフォールバックするため、既定値を変えると
     * 利用者がまだ触っていない項目の挙動まで変わります。ここでキーが無いものすべてに
     * {@link LinimalLegacyDefaults} の値を書き込み、変更前の挙動を保存済みの設定として固定します。</p>
     *
     * <p>新規インストールはこの migration を通りません（{@link #isFreshInstall} を参照）。</p>
     */
    private void migrateV2ToV3(Map<String, ?> values) {
        validateMigratableValues(values);

        Map<String, Object> updates = new HashMap<>();
        for (Map.Entry<LinimalFeature, Boolean> frozen
                : LinimalLegacyDefaults.featureStates().entrySet()) {
            String key = LinimalConfigSchema.keyFor(frozen.getKey());
            // 非推奨の alias は置き換え先とキーを共有します。凍結値は一致するため、先に入れた方を残します。
            if (values.containsKey(key) || updates.containsKey(key)) {
                continue;
            }
            updates.put(key, frozen.getValue());
        }
        if (!values.containsKey(LinimalConfigSchema.READ_RECEIPT_MODE_KEY)) {
            updates.put(
                    LinimalConfigSchema.READ_RECEIPT_MODE_KEY,
                    LinimalLegacyDefaults.READ_RECEIPT_MODE.storedValue());
        }
        updates.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, 3);
        write(updates);
    }

    /**
     * schema を進める前に、migration source、新しい destination、および既存の型付き値を検証します。
     * そのため不正な型は marker を更新する前に必ず fail-open になります。
     */
    private void validateMigratableValues(Map<String, ?> values) {
        readLegacyBoolean(
                values,
                LinimalConfigSchema.ADS_ENABLED_KEY,
                LinimalLegacyDefaults.isEnabled(LinimalFeature.ADS));
        readLegacyBoolean(
                values,
                LinimalConfigSchema.LINE_AI_ENABLED_KEY,
                LinimalLegacyDefaults.isEnabled(LinimalFeature.LINE_AI));
        for (LinimalFeature feature : LinimalFeature.values()) {
            readBoolean(values, feature);
        }
        readReadReceiptMode(values);
    }

    private int readSchemaVersion(Map<String, ?> values) {
        if (!values.containsKey(LinimalConfigSchema.SCHEMA_VERSION_KEY)) {
            return 0;
        }
        Object version = values.get(LinimalConfigSchema.SCHEMA_VERSION_KEY);
        if (!(version instanceof Integer)) {
            throw new ConfigStoreException("Invalid configuration schema version");
        }
        return (Integer) version;
    }

    private boolean readBoolean(Map<String, ?> values, LinimalFeature feature) {
        return readOptionalBoolean(
                values,
                LinimalConfigSchema.keyFor(feature),
                LinimalDefaults.isEnabled(feature));
    }

    private boolean readLegacyBoolean(Map<String, ?> values, String key, boolean defaultValue) {
        return readOptionalBoolean(values, key, defaultValue);
    }

    private boolean readNewValueOrLegacy(Map<String, ?> values, String key, boolean legacyValue) {
        return readOptionalBoolean(values, key, legacyValue);
    }

    private boolean readOptionalBoolean(Map<String, ?> values, String key, boolean defaultValue) {
        if (!values.containsKey(key)) {
            return defaultValue;
        }
        Object value = values.get(key);
        if (!(value instanceof Boolean)) {
            throw new ConfigStoreException("Invalid configuration value");
        }
        return (Boolean) value;
    }

    private ReadReceiptMode readReadReceiptMode(Map<String, ?> values) {
        if (!values.containsKey(LinimalConfigSchema.READ_RECEIPT_MODE_KEY)) {
            return LinimalDefaults.READ_RECEIPT_MODE;
        }
        Object value = values.get(LinimalConfigSchema.READ_RECEIPT_MODE_KEY);
        if (!(value instanceof String)) {
            throw new ConfigStoreException("Invalid read receipt mode");
        }
        return ReadReceiptMode.fromStoredValue((String) value);
    }

    private Map<String, ?> readAll() {
        try {
            Map<String, ?> values = backend.readAll();
            if (values == null) {
                throw new ConfigStoreException("Configuration storage returned no values");
            }
            return new HashMap<>(values);
        } catch (ConfigStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConfigStoreException("Unable to read configuration", exception);
        }
    }

    private void write(Map<String, Object> updates) {
        try {
            if (!backend.write(updates)) {
                throw new ConfigStoreException("Unable to persist configuration");
            }
        } catch (ConfigStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ConfigStoreException("Unable to persist configuration", exception);
        }
    }

    /** unit test と将来の置き換えのため、config package 内部に保持する storage adapter。 */
    interface PreferenceBackend {
        Map<String, ?> readAll();

        boolean write(Map<String, Object> updates);
    }

    private static final class SharedPreferencesBackend implements PreferenceBackend {
        private final SharedPreferences preferences;

        SharedPreferencesBackend(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public Map<String, ?> readAll() {
            return preferences.getAll();
        }

        @Override
        public boolean write(Map<String, Object> updates) {
            SharedPreferences.Editor editor = preferences.edit();
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) {
                    editor.putBoolean(entry.getKey(), (Boolean) value);
                } else if (value instanceof Integer) {
                    editor.putInt(entry.getKey(), (Integer) value);
                } else if (value instanceof String) {
                    editor.putString(entry.getKey(), (String) value);
                } else {
                    throw new ConfigStoreException("Unsupported configuration value type");
                }
            }
            return editor.commit();
        }
    }
}
