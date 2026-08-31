package dev.utaa.linimal.extension.config;

/** 保存済み設定を安全に使用できないことを示します。 */
final class ConfigStoreException extends RuntimeException {
    ConfigStoreException(String message) {
        super(message);
    }

    ConfigStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
