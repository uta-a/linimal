# Linimal の runtime extension は LINE のプロセスへ注入されるため、注入するコードは最小限に保ちます。
-dontobfuscate
-dontoptimize
-keepattributes *

# パッチが参照する Linimal の注入コードはすべて保持します。
-keep class dev.utaa.linimal.extension.** {
  *;
}

# extension コードから利用される Kotlin intrinsics は shrink 対象から除外します。
-keep class kotlin.jvm.internal.Intrinsics {
    public static *;
}

-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.element.Modifier
