package dev.utaa.linimal.patches.features.googleauth

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

internal const val MICROG_PACKAGE = "app.revanced.android.gms"
internal const val SPOOFED_SIGNATURE_META_DATA = "$MICROG_PACKAGE.SPOOFED_PACKAGE_SIGNATURE"

internal class GoogleAuthMicrogManifestException(reason: String) : Exception(reason)

/** AndroidManifest.xml へ、MicroG-RE に公式証明書を申告する meta-data と `<queries>` を加えます。 */
internal object GoogleAuthMicrogManifest {
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    /**
     * [certificateSha1] は区切りなし大文字 16 進の SHA-1。MicroG-RE は値をそのまま Google に送る
     * client_sig として使い、自身は小文字 16 進を送るため、小文字にして書きます。
     */
    fun register(document: Document, certificateSha1: String) {
        val manifest = document.documentElement
            ?: throw GoogleAuthMicrogManifestException("AndroidManifestRootMissing")
        if (manifest.nodeName != "manifest") {
            throw GoogleAuthMicrogManifestException("AndroidManifestRootInvalid")
        }
        // Morphe の manifest document は namespace を解決しないため、prefix 付きの属性名で扱います。
        if (manifest.getAttribute("xmlns:android") != ANDROID_NAMESPACE) {
            throw GoogleAuthMicrogManifestException("AndroidNamespaceMissing")
        }
        val application = directElements(manifest, "application").singleOrNull()
            ?: throw GoogleAuthMicrogManifestException("ApplicationNotUnique")
        val alreadyDeclared = directElements(application, "meta-data")
            .any { it.getAttribute("android:name") == SPOOFED_SIGNATURE_META_DATA }
        if (alreadyDeclared) {
            throw GoogleAuthMicrogManifestException("SpoofedSignatureAlreadyDeclared")
        }

        val metaData = document.createElement("meta-data")
        metaData.setAttribute("android:name", SPOOFED_SIGNATURE_META_DATA)
        metaData.setAttribute("android:value", certificateSha1.lowercase())
        application.appendChild(metaData)

        // Android 11 以上で MicroG-RE の存在確認と bind を行うため、package visibility を宣言します。
        val queries = document.createElement("queries")
        val microg = document.createElement("package")
        microg.setAttribute("android:name", MICROG_PACKAGE)
        queries.appendChild(microg)
        manifest.insertBefore(queries, application)
    }

    private fun directElements(parent: Element, name: String): List<Element> = buildList {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == name) {
                add(child as Element)
            }
        }
    }
}
