package dev.utaa.linimal.extension.config;

/** 現在 Linimal hook から参照できる runtime 設定の安全状態。 */
public enum LinimalConfigHealth {
    /** 設定の読み込みと検証に成功しました。 */
    OK,

    /** 設定を利用できないか無効であるため、hook は元の動作を維持しなければなりません。 */
    ERROR
}
