package dev.utaa.linimal.extension.features.googleauth;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 【PoC】Google ドライブ連携で使う {@code GoogleAuthUtil} の token 要求だけを、端末本体の
 * Google Play services から MicroG-RE へ向けます（ADR 0003 案）。
 *
 * <p>再署名した LINE では、端末本体の Google Play services が実際の署名で OAuth client を照合するため
 * token を取得できません。MicroG-RE は LINE の manifest の meta-data で申告された公式証明書の SHA-1 を
 * 使うので、{@code GetToken} の bind 先を MicroG-RE にすれば token を取得できる見込みです。</p>
 *
 * <p>MicroG-RE が未導入、または例外時は、LINE 本来の経路（端末本体の Google Play services）を
 * そのまま使います。FCM、FIS など他の GMS の呼び出しには触れません。</p>
 */
public final class GoogleAuthRoutingHooks {
    static final String GMS_PACKAGE = "com.google.android.gms";
    static final String MICROG_PACKAGE = "app.revanced.android.gms";
    static final String GET_TOKEN_CLASS = "com.google.android.gms.auth.GetToken";

    interface PackagePresence {
        boolean isInstalled(String packageName);
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
            return isRoutingActive(presenceOf(context));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** {@code GoogleAuthUtil} が {@code GetToken} を bind する直前で呼ばれ、bind 先を返します。 */
    public static ComponentName authServiceComponent(Context context, ComponentName original) {
        try {
            if (original == null) {
                return null;
            }
            String routed = routedPackage(
                    isRoutingActive(presenceOf(context)),
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

    static boolean isRoutingActive(PackagePresence presence) {
        return presence != null && presence.isInstalled(MICROG_PACKAGE);
    }

    /** 端末本体の Google Play services の {@code GetToken} だけを MicroG-RE の同名 component に向けます。 */
    static String routedPackage(boolean routingActive, String packageName, String className) {
        if (routingActive && GMS_PACKAGE.equals(packageName) && GET_TOKEN_CLASS.equals(className)) {
            return MICROG_PACKAGE;
        }
        return packageName;
    }

    private static PackagePresence presenceOf(Context context) {
        if (context == null) {
            return null;
        }
        final PackageManager packageManager = context.getPackageManager();
        return packageName -> {
            try {
                packageManager.getPackageInfo(packageName, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        };
    }
}
