package dev.utaa.linimal.extension.features.googleauth;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import dev.utaa.linimal.extension.config.LinimalConfig;

/**
 * Google ドライブ連携（トークのバックアップ・復元）で使う {@code GoogleAuthUtil} の token 要求だけを、
 * 端末本体の Google Play services から MicroG-RE へ向けます（ADR 0003）。
 *
 * <p>再署名した LINE では、端末本体の Google Play services が実際の署名で OAuth client を照合するため
 * token を取得できません。MicroG-RE は LINE の manifest の meta-data で申告された公式証明書の SHA-1 を
 * 使うので、{@code GetToken} の bind 先を MicroG-RE にすると token を取得できます。</p>
 *
 * <p>設定 OFF、未初期化、例外時は LINE 本来の経路（端末本体の Google Play services）をそのまま使います。
 * 設定 ON で MicroG-RE が未導入、または公式リリースの証明書で署名されていなければ元の経路を使い、
 * 導入を案内する通知を出します。FCM、FIS など他の GMS の呼び出しには触れません。</p>
 */
public final class GoogleAuthRoutingHooks {
    static final String GMS_PACKAGE = "com.google.android.gms";
    static final String MICROG_PACKAGE = "app.revanced.android.gms";
    static final String GET_TOKEN_CLASS = "com.google.android.gms.auth.GetToken";

    /**
     * MorpheApp/MicroG-RE の公式リリースの署名証明書（SHA-256）。同じパッケージ名の別アプリへ token の要求や
     * バックアップを渡さないよう、この証明書で署名された MicroG-RE だけを向け先にします。
     * 7.1.1 から 7.2.1-dev.2 までのリリースで同じ値であることを確認しています。
     */
    static final String MICROG_CERTIFICATE_SHA256 =
            "0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7";

    interface PackagePresence {
        /** [packageName] が入っていて、MicroG-RE の公式証明書で署名されているかどうか。 */
        boolean isTrustedInstalled(String packageName);
    }

    /** 設定と MicroG-RE の有無から決まる、token 要求の扱い。 */
    enum Routing {
        /** 設定 OFF。LINE 本来の経路を使います。 */
        ORIGINAL,
        /** 設定 ON で、公式証明書の MicroG-RE がない。LINE 本来の経路を使い、導入を案内します。 */
        MICROG_MISSING,
        /** 設定 ON で MicroG-RE がある。MicroG-RE へ向けます。 */
        MICROG
    }

    private GoogleAuthRoutingHooks() {
    }

    /**
     * {@code GoogleAuthUtil} の「GoogleAuthServiceClient を使うか」の判定の先頭で呼ばれます。
     * true を返すと判定を false にして、{@code GetToken} を bind する経路へ回します。
     * GoogleAuthServiceClient は bind 先を GMS client 共通の定数で決めるため、MicroG-RE へ向けられません。
     */
    public static boolean shouldSkipAuthServiceClient(Context context) {
        try {
            Routing routing = routingFor(context);
            if (routing == Routing.MICROG_MISSING) {
                MicrogInstallNotifier.notifyMissing(context);
            }
            return routing == Routing.MICROG;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * {@code GoogleAuthUtil} が {@code GetToken} を bind する直前で呼ばれ、bind 先を返します。
     *
     * <p>LINE の判定は別のフラグとの {@code &&} の後ろにあり、フラグが false だと
     * {@link #shouldSkipAuthServiceClient} は呼ばれません。そのため導入の案内はここからも出します。
     * 案内は一定時間に 1 回までなので、両方から呼ばれても重複しません。</p>
     */
    public static ComponentName authServiceComponent(Context context, ComponentName original) {
        try {
            if (original == null) {
                return null;
            }
            Routing routing = routingFor(context);
            if (routing == Routing.MICROG_MISSING && isGmsGetToken(original)) {
                MicrogInstallNotifier.notifyMissing(context);
            }
            String routed = routedPackage(
                    routing == Routing.MICROG,
                    original.getPackageName(),
                    original.getClassName());
            if (routed.equals(original.getPackageName())) {
                return original;
            }
            return new ComponentName(routed, original.getClassName());
        } catch (Throwable ignored) {
            return original;
        }
    }

    private static Routing routingFor(Context context) {
        return routing(LinimalConfig.get().isGoogleDriveBackupViaMicrogEnabled(), presenceOf(context));
    }

    static Routing routing(boolean enabled, PackagePresence presence) {
        if (!enabled || presence == null) {
            return Routing.ORIGINAL;
        }
        return presence.isTrustedInstalled(MICROG_PACKAGE) ? Routing.MICROG : Routing.MICROG_MISSING;
    }

    private static boolean isGmsGetToken(ComponentName component) {
        return GMS_PACKAGE.equals(component.getPackageName()) && GET_TOKEN_CLASS.equals(component.getClassName());
    }

    /** 端末本体の Google Play services の {@code GetToken} だけを MicroG-RE の同名 component に向けます。 */
    static String routedPackage(boolean routeToMicrog, String packageName, String className) {
        if (routeToMicrog && GMS_PACKAGE.equals(packageName) && GET_TOKEN_CLASS.equals(className)) {
            return MICROG_PACKAGE;
        }
        return packageName;
    }

    static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static PackagePresence presenceOf(Context context) {
        if (context == null) {
            return null;
        }
        final PackageManager packageManager = context.getPackageManager();
        final byte[] certificate = hexToBytes(MICROG_CERTIFICATE_SHA256);
        // 未導入でも false を返します。鍵のローテーション後の証明書も lineage で照合されます。
        return packageName -> packageManager.hasSigningCertificate(
                packageName, certificate, PackageManager.CERT_INPUT_SHA256);
    }
}
