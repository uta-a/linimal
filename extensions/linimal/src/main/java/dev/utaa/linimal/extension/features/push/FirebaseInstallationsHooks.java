package dev.utaa.linimal.extension.features.push;

import dev.utaa.linimal.extension.config.LinimalConfig;

/**
 * LINE に同梱された Firebase Installations (FIS) の登録リクエストで、{@code X-Android-Cert}
 * ヘッダーの値だけを公式 LINE の証明書 SHA-1 に置き換えます。
 *
 * <p>FIS はアプリ自身の署名から SHA-1 を計算してこのヘッダーに載せます。Google 側の API key は
 * 公式 LINE の証明書にしか許可されていないため、再署名した LINE では登録が拒否され、アプリを
 * 閉じている間の push 通知が届きません。GMS、PackageManager、LINE の認証と通信には触れず、
 * FIS の 1 ヘッダーだけを対象にします。</p>
 */
public final class FirebaseInstallationsHooks {
    private static final int CERTIFICATE_SHA1_LENGTH = 40;

    private FirebaseInstallationsHooks() {
    }

    /**
     * FIS の {@code addRequestProperty("X-Android-Cert", value)} の直前で呼ばれます。
     * 設定 OFF、未初期化、元の証明書が未設定、例外のいずれでも実際の値をそのまま返します。
     */
    public static String certificateHeader(String actual) {
        try {
            return certificateHeaderWith(
                    LinimalConfig.get().isPushNotificationRestoreEnabled(),
                    originalCertificateSha1(),
                    actual);
        } catch (Throwable ignored) {
            return actual;
        }
    }

    static String certificateHeaderWith(boolean enabled, String original, String actual) {
        if (!enabled || !isCertificateSha1(original)) {
            return actual;
        }
        return original;
    }

    /**
     * reference APKM の公式証明書の SHA-1 を返します。
     *
     * <p>ソース上は常に null です。bytecode patch が build 時にこのメソッドの先頭へ、証明書 SHA-1 を
     * 返す命令を注入します。値を LINE の version と対応づけて patch 側の 1 か所で管理するためで、
     * ここへ定数を書いてはいけません。</p>
     */
    static String originalCertificateSha1() {
        return null;
    }

    /** FIS が送るのと同じ、区切りなし大文字 16 進の SHA-1 だけを受け入れます。 */
    static boolean isCertificateSha1(String value) {
        if (value == null || value.length() != CERTIFICATE_SHA1_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
