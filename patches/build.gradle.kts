group = "dev.utaa.linimal"

patches {
    about {
        name = "Linimal"
        description = "LINE Android 向けの、実行時設定に対応した UI パッチ。"
        source = "na"
        author = "Linimal contributors"
        contact = "na"
        website = "na"
        license = "GPLv3"
    }
}

// APK にバンドルせずに generatePatchesList で Gson を利用できるようにします。
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
    testImplementation(kotlin("test"))
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch bundle metadata"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // 後で追加される template の semantic-release workflow で使用します。
    publish {
        dependsOn("generatePatchesList")
    }
}
