package dev.utaa.linimal.extension.features.push;

import android.content.Context;

import dev.utaa.linimal.extension.config.LinimalConfig;
import dev.utaa.linimal.extension.config.LinimalConfigBootstrap;

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
     *
     * <p>Firebase は ContentProvider から LINE の Application 初期化より前に動き出すため、設定がまだ
     * 初期化されていなければ、ここで初期化してから判定します。設定 OFF、初期化できない、元の証明書が
     * 未設定、例外のいずれでも実際の値をそのまま返します。</p>
     */
    public static String certificateHeader(String actual) {
        return certificateHeader(actual, FirebaseInstallationsHooks::initializeConfigIfNeeded);
    }

    static String certificateHeader(String actual, Runnable ensureConfigInitialized) {
        try {
            ensureConfigInitialized.run();
            return certificateHeaderWith(
                    LinimalConfig.get().isPushNotificationRestoreEnabled(),
                    originalCertificateSha1(),
                    actual);
        } catch (Throwable ignored) {
            return actual;
        }
    }

    private static void initializeConfigIfNeeded() {
        if (LinimalConfig.isInitializationAttempted()) {
            return;
        }
        Context application = currentApplication();
        if (application != null) {
            LinimalConfigBootstrap.initializeIfNeeded(application);
        }
    }

    /**
     * プロセスの Application を返します。ContentProvider の生成時点でも Application は作られていますが、
     * LINE の初期化 hook はまだ呼ばれていないため、ここから取ります。取れなければ null です。
     */
    private static Context currentApplication() {
        try {
            Object application = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
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
