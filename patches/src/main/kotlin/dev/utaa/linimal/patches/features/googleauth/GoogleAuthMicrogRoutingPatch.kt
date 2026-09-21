package dev.utaa.linimal.patches.features.googleauth

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import dev.utaa.linimal.patches.core.noOpProbePatch
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.OBJECT
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val CONTEXT = "Landroid/content/Context;"
private const val COMPONENT_NAME = "Landroid/content/ComponentName;"
private const val HOOKS_TYPE = "Ldev/utaa/linimal/extension/features/googleauth/GoogleAuthRoutingHooks;"
private const val SKIP_AUTH_SERVICE_CLIENT_HOOK = "$HOOKS_TYPE->shouldSkipAuthServiceClient($CONTEXT)$BOOLEAN"
private const val AUTH_SERVICE_COMPONENT_HOOK =
    "$HOOKS_TYPE->authServiceComponent($CONTEXT$COMPONENT_NAME)$COMPONENT_NAME"

/** GoogleAuthServiceClient を使える GMS の最小 version。GoogleAuthUtil の判定だけが持つ値です。 */
private const val AUTH_SERVICE_CLIENT_MIN_GMS_VERSION = 17895000

internal const val MICROG_PACKAGE = "app.revanced.android.gms"
internal const val SPOOFED_SIGNATURE_META_DATA = "$MICROG_PACKAGE.SPOOFED_PACKAGE_SIGNATURE"

/**
 * `GoogleAuthUtil` の「GoogleAuthServiceClient を使うか」の判定。`static boolean (Context)` で、
 * GMS の version を 17895000 と比べ、自分の packageName を除外リストと照合します。
 */
private val authServiceClientGateFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = BOOLEAN,
    parameters = listOf(CONTEXT),
    filters = listOf(
        literal(AUTH_SERVICE_CLIENT_MIN_GMS_VERSION),
        methodCall(name = "getApplicationInfo"),
    ),
)

/**
 * `GoogleAuthUtil` が `GetToken` などの component を bind して呼び出す処理。
 * `static Object (Context, ComponentName, <callback>)` で、bind 失敗時のログ文字列を持ちます。
 */
private val authServiceBindFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = OBJECT,
    parameters = listOf(CONTEXT, COMPONENT_NAME, "L"),
    filters = listOf(
        string("GoogleAuthUtil"),
        string("Could not bind to service."),
    ),
)

/**
 * 【PoC、鎖に入れない実験用】MicroG-RE が LINE を公式証明書で申告できるよう、manifest に
 * meta-data と、MicroG-RE を見つけるための `<queries>` を加えます（ADR 0003 案）。
 */
val googleAuthMicrogManifestPatch = resourcePatch(
    name = "【実験】Google 認証の MicroG-RE 用 manifest",
    description = "MicroG-RE に公式 LINE の証明書を申告する meta-data を追加します。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)

    execute {
        val certificate = Constants.LINE_ORIGINAL_CERTIFICATE_SHA1
        if (!Regex("[0-9A-F]{40}").matches(certificate)) {
            throw PatchException("LINE_ORIGINAL_CERTIFICATE_SHA1 is not configured.")
        }
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val application = directElements(manifest, "application").singleOrNull()
                ?: throw PatchException("AndroidManifest must have exactly one application.")
            val alreadyDeclared = directElements(application, "meta-data")
                .any { it.getAttribute("android:name") == SPOOFED_SIGNATURE_META_DATA }
            if (alreadyDeclared) {
                throw PatchException("Spoofed signature meta-data is already declared.")
            }

            // MicroG-RE は小文字 16 進を Google に送る client_sig としてそのまま使います。
            val metaData = document.createElement("meta-data")
            metaData.setAttribute("android:name", SPOOFED_SIGNATURE_META_DATA)
            metaData.setAttribute("android:value", certificate.lowercase())
            application.appendChild(metaData)

            val queries = document.createElement("queries")
            val microg = document.createElement("package")
            microg.setAttribute("android:name", MICROG_PACKAGE)
            queries.appendChild(microg)
            manifest.insertBefore(queries, application)
        }
    }
}

/**
 * 【PoC、鎖に入れない実験用】Google ドライブ連携の token 要求だけを MicroG-RE へ向けます。
 * GoogleAuthServiceClient の経路を使わせず、`GetToken` の bind 先を hook で差し替えます。
 * MicroG-RE が未導入なら hook は元の値を返します。
 */
@Suppress("unused")
val googleAuthMicrogRoutingPatch = bytecodePatch(
    name = "【実験】Google 認証を MicroG-RE へ向ける",
    description = "Google ドライブ連携の token 要求を MicroG-RE へ向けます。PoC 用で、実機検証以外に使わないでください。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.DISABLED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(noOpProbePatch, googleAuthMicrogManifestPatch)

    execute {
        val gates = authServiceClientGateFingerprint.matchAllOrNull().orEmpty()
        val binds = authServiceBindFingerprint.matchAllOrNull().orEmpty()
        if (gates.size != 1 || binds.size != 1) {
            throw PatchException("GoogleAuthUtil targets are not unique: gate=${gates.size}, bind=${binds.size}")
        }

        val gate = gates.single().method
        val gateRegisters = gate.implementation?.registerCount ?: 0
        // 引数は Context 1 つ（static）。先頭で結果を置く local register が 1 つ要ります。
        if (gateRegisters < 2) {
            throw PatchException("GoogleAuthUtil gate has no local register.")
        }
        val contextRegister = gateRegisters - 1
        gate.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { v$contextRegister .. v$contextRegister }, $SKIP_AUTH_SERVICE_CLIENT_HOOK
                move-result v0
                if-eqz v0, :keep
                const/4 v0, 0x0
                return v0
                :keep
                nop
            """.trimIndent(),
        )

        val bind = binds.single().method
        val bindRegisters = bind.implementation?.registerCount ?: 0
        // 引数は Context、ComponentName、callback の 3 つ（static）で、末尾 3 つの register に入ります。
        val contextParam = bindRegisters - 3
        val componentParam = bindRegisters - 2
        if (contextParam < 0) {
            throw PatchException("GoogleAuthUtil bind has unexpected registers.")
        }
        // 高位 register を非 range の invoke に渡さないよう、range で呼びます。
        bind.addInstructions(
            0,
            """
                invoke-static/range { v$contextParam .. v$componentParam }, $AUTH_SERVICE_COMPONENT_HOOK
                move-result-object v$componentParam
            """.trimIndent(),
        )
    }
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
