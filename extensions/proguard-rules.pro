# Linimal の runtime extension は LINE のプロセスへ注入されるため、注入するコードは最小限に保ちます。
-dontobfuscate
-dontoptimize
-keepattributes *

# パッチが参照する Linimal の注入コードはすべて保持します。
-keep class dev.utaa.linimal.extension.** {
  *;
}

# Linimal の extension は Java のみで実装するため、Kotlin stdlib は注入しません。
# LINE 本体に同名のクラスが存在するため、重複した定義を持ち込まないようにします。
-dontwarn kotlin.**

-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.element.Modifier
