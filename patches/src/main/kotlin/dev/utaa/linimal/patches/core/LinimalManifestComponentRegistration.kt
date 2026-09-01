package dev.utaa.linimal.patches.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** AndroidManifest.xml へ Linimal の内部 Activity を安全に登録します。 */
object LinimalManifestComponentRegistration {
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val SETTINGS_ACTIVITY = "dev.utaa.linimal.extension.settings.LinimalSettingsActivity"

    /** ActionBar なしの公開プラットフォームテーマ。配色は Activity 側の LinimalPalette が決めます。 */
    private const val SETTINGS_ACTIVITY_THEME = "@android:style/Theme.DeviceDefault.Light.NoActionBar"

    fun register(document: Document) {
        val manifest = document.documentElement
            ?: throw ManifestComponentRegistrationException("AndroidManifestRootMissing")
        if (manifest.nodeName != "manifest") {
            throw ManifestComponentRegistrationException("AndroidManifestRootInvalid")
        }
        // Morphe の manifest document は namespace を解決しないため、prefix 付きの属性名で扱います。
        if (manifest.getAttribute("xmlns:android") != ANDROID_NAMESPACE) {
            throw ManifestComponentRegistrationException("AndroidNamespaceMissing")
        }

        val applications = directElements(manifest, "application")
        val application = when (applications.size) {
            1 -> applications.single()
            0 -> throw ManifestComponentRegistrationException("ApplicationMissing")
            else -> throw ManifestComponentRegistrationException("ApplicationMultiple")
        }

        if (hasComponentNamed(application, SETTINGS_ACTIVITY)) {
            throw ManifestComponentRegistrationException("SettingsActivityAlreadyRegistered")
        }

        val activity = document.createElement("activity")
        activity.setAttribute("android:name", SETTINGS_ACTIVITY)
        activity.setAttribute("android:exported", "false")
        activity.setAttribute("android:excludeFromRecents", "true")
        activity.setAttribute("android:label", "Linimal")
        // 設定画面はヘッダーを自前で描画するため、ActionBar のないテーマで登録します。
        // ActionBar 付きのテーマでは android:label がタイトルとして重なり、ヘッダーを覆います。
        activity.setAttribute("android:theme", SETTINGS_ACTIVITY_THEME)
        application.appendChild(activity)
    }

    private fun hasComponentNamed(application: Element, componentName: String): Boolean =
        directElements(application)
            .filter { it.nodeName == "activity" || it.nodeName == "activity-alias" }
            .any { it.getAttribute("android:name") == componentName }

    private fun directElements(parent: Element, name: String? = null): List<Element> = buildList {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE && (name == null || child.nodeName == name)) {
                add(child as Element)
            }
        }
    }
}

/** manifest を変更せず mandatory core failure にする安全性違反です。 */
class ManifestComponentRegistrationException(message: String) : IllegalStateException(message)
