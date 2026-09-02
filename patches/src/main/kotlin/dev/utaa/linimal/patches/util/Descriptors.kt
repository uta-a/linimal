package dev.utaa.linimal.patches.util

/*
 * JVM 標準型の descriptor をここへ集約します。
 *
 * ここに置くのは primitive と `java.lang` / `java.util` の JDK 標準型だけです。これらは LINE の
 * version が変わっても値が変わらないため、1 箇所へまとめても各パッチが読みにくくなりません。
 * 逆に同じ名前が別ファイルで別の値を持つ事故は防げます。実際に `BOOLEAN` が 11 ファイルでは
 * primitive の `"Z"`、1 ファイルだけ boxed の `"Ljava/lang/Boolean;"` を指しており、fingerprint を
 * ファイル間でコピーすると意味が黙って変わる状態でした。boxed 型は [BOXED_BOOLEAN] として
 * 名前で区別します。
 *
 * `Lh3/t;` のような**難読化された LINE の型名はここへ置きません**。難読化名は version ごとに変わる
 * 値であり、共通化すると「どのパッチがどの型に依存しているか」が読み取れなくなって、version 追従時に
 * 影響範囲を追えなくなります。Android / androidx / Kotlin stdlib の型も、JVM 標準ではなく対象アプリ側の
 * 依存であるため、各パッチのファイルに残します。
 *
 * patches jar は publish されるため、公開 API を広げないよう `internal` に限定します。
 */

/** `void` */
internal const val VOID = "V"

/** `boolean`。boxed 型が必要な場合は [BOXED_BOOLEAN] を使います。 */
internal const val BOOLEAN = "Z"

/** `int` */
internal const val INT = "I"

/** `long` */
internal const val LONG = "J"

/** `java.lang.Object` */
internal const val OBJECT = "Ljava/lang/Object;"

/** `java.lang.Object[]` */
internal const val OBJECT_ARRAY = "[Ljava/lang/Object;"

/** `java.lang.String` */
internal const val STRING = "Ljava/lang/String;"

/** `java.lang.Boolean`。primitive の `"Z"` は [BOOLEAN] です。 */
internal const val BOXED_BOOLEAN = "Ljava/lang/Boolean;"

/** `java.lang.Integer` */
internal const val INTEGER = "Ljava/lang/Integer;"

/** `java.util.List` */
internal const val LIST = "Ljava/util/List;"

/** `java.util.HashMap` */
internal const val HASH_MAP = "Ljava/util/HashMap;"
