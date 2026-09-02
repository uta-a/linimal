package dev.utaa.linimal.extension.features;

/**
 * Home の module を Compose 前に抑制するかどうかを、fail-open で判定する共通 gate です。
 *
 * <p>どの設定を読むかは module ごとに異なるため、判定そのものは各 hook が
 * {@link SuppressionState} として渡します。この class は fail-open の扱いだけを引き受けます。</p>
 */
final class HomeSuppressionGate {
    /** 設定の読み取り経路。test から差し替えられるよう、hook 本体とは分離しています。 */
    interface SuppressionState {
        boolean isSuppressionEnabled() throws Throwable;
    }

    private HomeSuppressionGate() {
    }

    /**
     * state が無い場合、または設定読み取り時の例外では false を返し、LINE の元の renderer を実行します。
     */
    static boolean shouldSuppress(SuppressionState state) {
        if (state == null) {
            return false;
        }
        try {
            return state.isSuppressionEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
