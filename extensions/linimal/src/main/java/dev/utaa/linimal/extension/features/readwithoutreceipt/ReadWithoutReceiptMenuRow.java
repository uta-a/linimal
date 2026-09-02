package dev.utaa.linimal.extension.features.readwithoutreceipt;

/**
 * 「既読をつけずに読む」行そのものを描画する Compose コードの置き場です。
 *
 * <p>Java 側には何も実装がありません。bytecode patch が build 時に、LINE 内の既存のメニュー行を
 * 複製した {@code public static render(Lvb8/a;Ljava/lang/Object;Lh3/t;)V} をこのクラスへ追加します
 * （第 1 引数は行の onClick となる難読化された Kotlin {@code Function0}、第 2 引数はラベル描画の
 * ラムダ、第 3 引数は Compose の {@code Composer}）。トーク一覧の長押しメニュー側は、この
 * {@code render} を呼ぶ 1 命令だけを注入されます。</p>
 *
 * <p>空のクラスは shrinker に除去されやすいので、{@code extensions/proguard-rules.pro} の
 * keep ルールも合わせて維持してください。</p>
 */
public final class ReadWithoutReceiptMenuRow {
    private ReadWithoutReceiptMenuRow() {
    }
}
