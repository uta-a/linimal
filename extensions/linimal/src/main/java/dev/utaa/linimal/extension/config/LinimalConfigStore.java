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

    private void ensureCurrentSchema(Map<String, ?> values) {
        int version = readSchemaVersion(values);
        while (version < LinimalConfigSchema.CURRENT_VERSION) {
            switch (version) {
                case 0:
                    migrateV0ToV1();
                    version = 1;
                    break;
                default:
                    throw new ConfigStoreException("Unsupported configuration schema version");
            }
        }
        if (version != LinimalConfigSchema.CURRENT_VERSION) {
            throw new ConfigStoreException("Unsupported configuration schema version");
        }
    }

    /** v0 には schema marker がありませんでした。既存の v1-compatible な値は変更せず保持します。 */
    private void migrateV0ToV1() {
        Map<String, Object> updates = new HashMap<>();
        updates.put(LinimalConfigSchema.SCHEMA_VERSION_KEY, LinimalConfigSchema.CURRENT_VERSION);
        write(updates);
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
        String key = LinimalConfigSchema.keyFor(feature);
        if (!values.containsKey(key)) {
            return LinimalDefaults.isEnabled(feature);
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
