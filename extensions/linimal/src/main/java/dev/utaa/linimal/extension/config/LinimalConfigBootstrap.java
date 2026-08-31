package dev.utaa.linimal.extension.config;

import android.content.Context;

/** プロセスローカルな Linimal 設定に対する internal core の初期化境界。 */
public final class LinimalConfigBootstrap {
    private LinimalConfigBootstrap() {
    }

    /** Android の storage object を feature hook に公開せずに設定を初期化します。 */
    public static void initialize(Context context) {
        LinimalConfig.initialize(context);
    }
}
