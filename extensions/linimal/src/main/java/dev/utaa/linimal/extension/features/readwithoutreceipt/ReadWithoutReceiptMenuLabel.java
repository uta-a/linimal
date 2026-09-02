package dev.utaa.linimal.extension.features.readwithoutreceipt;

/**
 * 「既読をつけずに読む」行のラベルを描画する Compose ラムダの器です。
 *
 * <p>Java 側には何も実装がありません。bytecode patch が build 時にこのクラスへ次の 2 つを加えます。</p>
 *
 * <ol>
 *   <li>{@code interfaces} へ難読化された Kotlin {@code Function2}（{@code Lvb8/p;}）を追加する。</li>
 *   <li>{@code invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;} を LINE 内の既存の
 *   ラベル描画ラムダから複製して追加する。複製した本体は表示する文字列として
 *   {@link ChatListMenuHooks#menuLabel()} を呼びます。</li>
 * </ol>
 *
 * <p>この前提のため、ソース上で {@code invoke} を定義してはいけません。patch が複製した実装と
 * 衝突します。空のクラスは shrinker に除去されやすいので、{@code extensions/proguard-rules.pro} の
 * keep ルールも合わせて維持してください。</p>
 */
public final class ReadWithoutReceiptMenuLabel {
    /** bytecode patch が {@code new-instance} + {@code invoke-direct} で使う public な no-arg constructor。 */
    public ReadWithoutReceiptMenuLabel() {
    }
}
