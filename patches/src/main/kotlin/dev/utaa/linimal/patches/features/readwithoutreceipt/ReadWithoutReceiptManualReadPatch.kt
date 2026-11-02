package dev.utaa.linimal.patches.features.readwithoutreceipt

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import dev.utaa.linimal.patches.features.readreceipts.outboundGateFingerprint
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnappliedFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val STRING = "Ljava/lang/String;"
private const val CONTEXT = "Landroid/content/Context;"
private const val HASH_MAP = "Ljava/util/HashMap;"
private const val OBJECT = "Ljava/lang/Object;"
private const val SQLITE_DATABASE = "Landroid/database/sqlite/SQLiteDatabase;"
private const val PREFERENCES = "Landroid/content/SharedPreferences;"
private const val PREFERENCES_EDITOR = "Landroid/content/SharedPreferences\$Editor;"
internal const val MANUAL_READ = "linimalMarkReadManually"
internal const val CLEAR_STALE_FAILED_READ = "linimalClearStaleFailedRead"
internal const val WAS_READ_SENT = "linimalWasReadSent"
internal const val MAIN_DATABASE = "linimalMainDatabase"
internal const val OWN_MID = "linimalOwnMid"
internal const val NOTIFY_CHAT_LIST = "linimalNotifyChatListUpdated"
private val GENERATED_NAMES = setOf(MANUAL_READ, CLEAR_STALE_FAILED_READ, WAS_READ_SENT, MAIN_DATABASE,
    OWN_MID, NOTIFY_CHAT_LIST)

/**
 * 公式 executor へ手動既読の入口を追加します。通信と既存メソッドの本文は変更しません。
 * 部分既読だけは送信前に同じトークの古い FAILED を消し、送信後に実際の結果を確認します。
 */
val readWithoutReceiptManualReadPatch = bytecodePatch(
    name = "既読なし閲覧中の手動既読入口",
    description = "選択したメッセージと現在のトーク全体を、公式の既読処理へ渡す入口を追加します。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) PatchAvailability.REQUIRED else PatchAvailability.UNAVAILABLE
    }
    dependsOn(readWithoutReceiptSendPatch)
    execute {
        val ids = listOf(PatchId.READ_WITHOUT_RECEIPT_MANUAL_READ,
            PatchId.NOTIFICATION_READ_WITHOUT_RECEIPT_MANUAL_READ)
        val outbounds = outboundGateFingerprint.matchAllOrNull().orEmpty()
        if (outbounds.size != 1) {
            recordUnappliedFeatureStatus(ids, 2, outbounds.size, "ManualReadExecutorNotUnique")
            return@execute
        }
        val outbound = outbounds.single()
        // 難読化名ではなく、既存 outbound と同じ owner 内の検索契約を使います。
        val getters = Fingerprint(
            definingClass = outbound.originalClassDef.type,
            parameters = listOf(STRING), returnType = "J",
            custom = { method, _ -> isManualReadPointGetter(method) },
        ).matchAllOrNull().orEmpty()
        val actual = 1 + getters.size
        if (getters.size != 1) {
            recordUnappliedFeatureStatus(ids, 2, actual, "ManualReadGetterNotUnique")
            return@execute
        }
        val getter = getters.single().originalMethod
        if (!AccessFlags.PUBLIC.isSet(outbound.originalMethod.accessFlags) ||
            AccessFlags.STATIC.isSet(outbound.originalMethod.accessFlags) ||
            outbound.originalClassDef.methods.any { it.name in GENERATED_NAMES }) {
            recordUnsafeFeatureStatus(ids, 2, actual, "ManualReadBridgeShapeMismatch")
            return@execute
        }
        // gate 注入後の本文でも読めるよう、位置ではなく参照の組で公式の FAILED queue を特定します。
        val queue = manualReadQueueReferences(outbound.method)
        val ownMid = manualReadOwnMidReferences(getter)
        val context = outbound.originalClassDef.fields.filter {
            it.type == CONTEXT && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        if (queue == null || ownMid == null || context.size != 1) {
            recordUnsafeFeatureStatus(ids, 2, actual, "ManualReadQueueShapeMismatch")
            return@execute
        }
        // 接続は LINE 本体の helper が持つものを使います。enum の値は公式の DB 名で特定します。
        val databaseEnums = Fingerprint(strings = listOf("naver_line", "db.main")).matchAllOrNull().orEmpty()
        val databaseEnum = databaseEnums.singleOrNull()?.originalClassDef
        val mainField = databaseEnum?.fields?.singleOrNull { it.name == "MAIN" && it.type == databaseEnum.type }
        val databaseGetters = if (databaseEnum == null) emptyList() else Fingerprint(
            parameters = listOf(databaseEnum.type), returnType = SQLITE_DATABASE,
            custom = { method, _ -> isWritableDatabaseGetter(method, databaseEnum.type) },
        ).matchAllOrNull().orEmpty()
        // 一覧更新は d が使う公式 Runnable と同じ bus・同じ取得方法に揃えます。
        val refreshType = manualReadRefreshType(outbound.method)
        val refreshRuns = if (refreshType == null) emptyList() else Fingerprint(
            definingClass = refreshType, name = "run", parameters = emptyList(), returnType = "V",
        ).matchAllOrNull().orEmpty()
        val bus = refreshRuns.singleOrNull()?.originalMethod?.let(::manualReadBusReferences)
        // MainActivity の @Subscribe 名は反射で使われるため難読化されません。
        val badgeEvents = Fingerprint(name = "onUpdateMainChatTabBadgeEvent", returnType = "V",
            custom = { method, _ -> method.parameterTypes.size == 1 }).matchAllOrNull().orEmpty()
        val badgeEventType = badgeEvents.singleOrNull()?.originalMethod?.parameterTypes?.single()?.toString()
        val badgeEventOwners = if (badgeEventType == null) emptyList() else Fingerprint(
            definingClass = badgeEventType, name = "<clinit>",
        ).matchAllOrNull().orEmpty()
        val badgeEvent = badgeEventOwners.singleOrNull()?.originalClassDef?.fields?.singleOrNull {
            it.type == badgeEventType && AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val databaseGetter = databaseGetters.singleOrNull()?.originalMethod
        if (mainField == null || databaseGetter == null || bus == null || badgeEvent == null) {
            recordUnsafeFeatureStatus(ids, 2, actual, "ManualReadBadgeTargetsNotUnique")
            return@execute
        }
        // 全条件を確認してから変更します。実行時の許可は worker と session token に限定されます。
        val owner = outbound.originalClassDef.type
        val generated = listOf(
            manualReadBridge(outbound.originalMethod, getter),
            clearStaleFailedRead(owner, queue),
            wasReadSent(owner, queue),
            mainDatabaseGetter(owner, reference(mainField), databaseGetter),
            ownMidGetter(owner, ownMid),
            notifyChatListUpdated(owner, reference(context.single()), bus, reference(badgeEvent)),
        )
        generated.forEach {
            outbound.classDef.methods.add(it)
            outbound.classDef.virtualMethods.add(it)
        }
        recordFeatureStatus(ids, 2, 2, "ManualReadBridgeInjected")
    }
}

/** parseLong → chat/profile ごとの List 検索 → 空判定 → 先頭の long という公式 getter の契約。 */
internal fun isManualReadPointGetter(method: Method): Boolean {
    if (method.parameterTypes != listOf(STRING) || method.returnType != "J" ||
        !AccessFlags.PUBLIC.isSet(method.accessFlags) || !AccessFlags.FINAL.isSet(method.accessFlags) ||
        AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val instructions = method.implementation?.instructions?.toList() ?: return false
    val calls = instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
    fun has(owner: String, name: String, parameters: List<String>, result: String) = calls.any {
        it.definingClass == owner && it.name == name && it.parameterTypes == parameters && it.returnType == result
    }
    return has("Ljava/lang/Long;", "parseLong", listOf(STRING), "J") &&
        has("Ljava/util/List;", "isEmpty", emptyList(), "Z") &&
        has("Ljava/util/List;", "get", listOf("I"), "Ljava/lang/Object;") &&
        calls.count { it.parameterTypes == listOf("J", STRING, STRING) && it.returnType == "Ljava/util/List;" } == 1 &&
        calls.count { it.parameterTypes.isEmpty() && it.returnType == "J" } == 1 &&
        instructions.count { it.opcode == Opcode.RETURN_WIDE } == 2
}

/** 公式 d が FAILED queue を読むときの参照です。entry の field は private なので accessor を使います。 */
internal data class ManualReadQueueReferences(
    val queue: FieldReference,
    val load: MethodReference,
    val map: FieldReference,
    val entryType: String,
    val readPoint: MethodReference,
    val state: MethodReference,
    val failed: FieldReference,
    val succeeded: FieldReference,
    val store: FieldReference,
    val preferences: FieldReference,
)

internal data class ManualReadOwnMidReferences(
    val account: FieldReference,
    val profile: MethodReference,
    val mid: FieldReference,
)

internal data class ManualReadBusReferences(
    val serviceKey: FieldReference,
    val service: MethodReference,
    val busType: String,
    val chatList: FieldReference,
    val post: MethodReference,
)

/** gate の有無で位置が変わるため、公式 d の HashMap.get と Editor.remove の周辺だけで判定します。 */
internal fun manualReadQueueReferences(method: Method): ManualReadQueueReferences? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val get = instructions.indexOfFirst {
        val call = it.methodRef()
        call?.definingClass == HASH_MAP && call.name == "get"
    }
    if (get < 2) return null
    val map = instructions[get - 1].fieldRef() ?: return null
    val queueType = map.definingClass
    val entryType = ((instructions.getOrNull(get + 2) as? ReferenceInstruction)?.reference as? TypeReference)?.type
    if (instructions[get - 1].opcode != Opcode.IGET_OBJECT || map.type != HASH_MAP ||
        instructions.getOrNull(get + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        instructions.getOrNull(get + 2)?.opcode != Opcode.CHECK_CAST || entryType == null) return null
    val queue = instructions.mapNotNull { it.fieldRef() }.distinct().singleOrNull {
        it.definingClass == method.definingClass && it.type == queueType
    } ?: return null
    val load = instructions[get - 2].methodRef()?.takeIf {
        it.definingClass == queueType && it.parameterTypes.isEmpty() && it.returnType == "V"
    } ?: return null
    val accessors = instructions.mapNotNull { it.methodRef() }.distinct().filter {
        it.definingClass == entryType && it.parameterTypes.map(CharSequence::toString) == listOf(entryType)
    }
    val readPoint = accessors.singleOrNull { it.returnType == "J" } ?: return null
    val state = accessors.singleOrNull { it.returnType != "J" } ?: return null
    val states = instructions.mapNotNull { it.fieldRef() }.distinct().filter {
        it.definingClass == state.returnType && it.type == state.returnType
    }
    val failed = states.singleOrNull { it.name == "FAILED" } ?: return null
    val succeeded = states.singleOrNull { it.name == "SUCCEEDED" } ?: return null
    val remove = instructions.indexOfFirst {
        val call = it.methodRef()
        call?.definingClass == PREFERENCES_EDITOR && call.name == "remove"
    }
    if (remove < 4) return null
    val store = instructions[remove - 4].fieldRef() ?: return null
    val preferences = instructions[remove - 3].fieldRef() ?: return null
    if (store.definingClass != queueType || preferences.definingClass != store.type ||
        preferences.type != PREFERENCES) return null
    return ManualReadQueueReferences(queue, load, map, entryType, readPoint, state, failed, succeeded,
        store, preferences)
}

/** 公式の未読検索が自分の mid として渡す値と同じ参照を、register の流れまで確認して取り出します。 */
internal fun manualReadOwnMidReferences(getter: Method): ManualReadOwnMidReferences? {
    val instructions = getter.implementation?.instructions?.toList() ?: return null
    val profile = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_INTERFACE && it.methodRef()?.name == "getProfile" }
    if (profile < 1) return null
    val account = instructions[profile - 1].fieldRef() ?: return null
    val midRead = instructions.getOrNull(profile + 2) as? TwoRegisterInstruction ?: return null
    val mid = instructions[profile + 2].fieldRef() ?: return null
    val query = instructions.singleOrNull {
        it.methodRef()?.parameterTypes?.map(CharSequence::toString) == listOf("J", STRING, STRING)
    } as? FiveRegisterInstruction ?: return null
    val returned = (instructions.getOrNull(profile + 1) as? OneRegisterInstruction)?.registerA
    if (instructions[profile - 1].opcode != Opcode.IGET_OBJECT ||
        instructions[profile + 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
        instructions[profile + 2].opcode != Opcode.IGET_OBJECT || mid.type != STRING ||
        midRead.registerB != returned || query.registerF != midRead.registerA) return null
    return ManualReadOwnMidReferences(account, checkNotNull(instructions[profile].methodRef()), mid)
}

/** 公式 d のローカル既読直後に呼ばれる一覧更新 Runnable の型です。 */
internal fun manualReadRefreshType(method: Method): String? = method.implementation?.instructions
    ?.mapNotNull { it.methodRef() }?.toList().orEmpty()
    .filter { it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V" }
    .map { it.definingClass }.distinct().singleOrNull()

/** 公式 Runnable と同じ DI key・check-cast・CHAT_LIST 投稿の組だけを受け入れます。 */
internal fun manualReadBusReferences(run: Method): ManualReadBusReferences? {
    val instructions = run.implementation?.instructions?.toList() ?: return null
    val chatListIndex = instructions.indexOfFirst {
        it.opcode == Opcode.SGET_OBJECT && it.fieldRef()?.name == "CHAT_LIST"
    }
    if (chatListIndex < 4) return null
    val chatList = checkNotNull(instructions[chatListIndex].fieldRef())
    val serviceKey = instructions[chatListIndex - 4].fieldRef()
    val service = instructions[chatListIndex - 3].methodRef()
    val busType = ((instructions[chatListIndex - 1] as? ReferenceInstruction)?.reference as? TypeReference)?.type
    val post = instructions.getOrNull(chatListIndex + 1)?.methodRef()
    if (instructions[chatListIndex - 4].opcode != Opcode.SGET_OBJECT || serviceKey == null ||
        instructions[chatListIndex - 3].opcode != Opcode.INVOKE_STATIC || service == null ||
        service.parameterTypes.size != 2 || service.parameterTypes[0].toString() != CONTEXT ||
        service.returnType != OBJECT || instructions[chatListIndex - 2].opcode != Opcode.MOVE_RESULT_OBJECT ||
        instructions[chatListIndex - 1].opcode != Opcode.CHECK_CAST || busType == null ||
        serviceKey.definingClass != busType || post == null ||
        instructions[chatListIndex + 1].opcode != Opcode.INVOKE_INTERFACE || post.definingClass != busType ||
        post.parameterTypes.map(CharSequence::toString) != listOf(OBJECT) || post.returnType != "V") return null
    return ManualReadBusReferences(serviceKey, service, busType, chatList, post)
}

private fun isWritableDatabaseGetter(method: Method, enumType: String): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.map(CharSequence::toString) == listOf(enumType) &&
        method.implementation?.instructions?.any {
            it.methodRef()?.name == "getWritableDatabase" && it.methodRef()?.returnType == SQLITE_DATABASE
        } == true

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun reference(field: Field) = ImmutableFieldReference(field.definingClass, field.name, field.type)

private fun smali(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"

private fun smali(method: MethodReference) = method.definingClass + "->" + method.name +
    method.parameterTypes.joinToString("", "(", ")") + method.returnType

/**
 * 部分既読の位置以上の FAILED が残っていると、d は押した位置を保持値へ置き換えて送ります。
 * その保持値だけを map と永続 prefs から消します。古い FAILED は d がそのまま上書きします。
 */
internal fun clearStaleFailedRead(owner: String, refs: ManualReadQueueReferences): MutableMethod =
    guardedQueueMethod(owner, CLEAR_STALE_FAILED_READ, "V", 8, BuilderInstruction10x(Opcode.RETURN_VOID), """
        iget-object v0, p0, ${smali(refs.queue)}
        monitor-enter v0
        invoke-virtual {v0}, ${smali(refs.load)}
        iget-object v1, v0, ${smali(refs.map)}
        invoke-virtual {v1, p3}, $HASH_MAP->get($OBJECT)$OBJECT
        move-result-object v1
        if-eqz v1, :unlock
        check-cast v1, ${refs.entryType}
        invoke-static {v1}, ${smali(refs.state)}
        move-result-object v2
        sget-object v3, ${smali(refs.failed)}
        if-ne v2, v3, :unlock
        invoke-static {v1}, ${smali(refs.readPoint)}
        move-result-wide v2
        cmp-long v2, v2, p1
        if-ltz v2, :unlock
        iget-object v1, v0, ${smali(refs.map)}
        invoke-virtual {v1, p3}, $HASH_MAP->remove($OBJECT)$OBJECT
        iget-object v1, v0, ${smali(refs.store)}
        iget-object v1, v1, ${smali(refs.preferences)}
        invoke-interface {v1}, $PREFERENCES->edit()$PREFERENCES_EDITOR
        move-result-object v1
        invoke-interface {v1, p3}, $PREFERENCES_EDITOR->remove($STRING)$PREFERENCES_EDITOR
        move-result-object v1
        invoke-interface {v1}, $PREFERENCES_EDITOR->apply()V
        :unlock
        monitor-exit v0
    """)

/** d は RPC 失敗と空振りを戻り値で返さないため、queue の結果が押した位置の SUCCEEDED かを確認します。 */
internal fun wasReadSent(owner: String, refs: ManualReadQueueReferences): MutableMethod =
    guardedQueueMethod(owner, WAS_READ_SENT, "Z", 9, BuilderInstruction11x(Opcode.RETURN, 4), """
        const/4 v4, 0x0
        iget-object v0, p0, ${smali(refs.queue)}
        monitor-enter v0
        invoke-virtual {v0}, ${smali(refs.load)}
        iget-object v1, v0, ${smali(refs.map)}
        invoke-virtual {v1, p3}, $HASH_MAP->get($OBJECT)$OBJECT
        move-result-object v1
        if-eqz v1, :unlock
        check-cast v1, ${refs.entryType}
        invoke-static {v1}, ${smali(refs.readPoint)}
        move-result-wide v2
        cmp-long v2, v2, p1
        if-nez v2, :unlock
        invoke-static {v1}, ${smali(refs.state)}
        move-result-object v2
        sget-object v3, ${smali(refs.succeeded)}
        if-ne v2, v3, :unlock
        const/4 v4, 0x1
        :unlock
        monitor-exit v0
    """)

/** monitor を例外時にも解放して投げ直します。bridge の catch が 0 を返すので、不明な状態では送信しません。 */
private fun guardedQueueMethod(owner: String, name: String, result: String, registers: Int,
    terminal: BuilderInstruction, body: String): MutableMethod {
    val method = generatedMethod(owner, name, listOf("J", STRING), result, registers, terminal)
    method.addInstructions(0, body.trimIndent())
    val implementation = checkNotNull(method.implementation)
    val handler = implementation.instructions.size
    method.addInstructions(handler, """
        move-exception v1
        monitor-exit v0
        throw v1
    """.trimIndent())
    val instructions = implementation.instructions
    val enter = instructions.indexOfFirst { it.opcode == Opcode.MONITOR_ENTER }
    val exit = instructions.indexOfFirst { it.opcode == Opcode.MONITOR_EXIT }
    implementation.addCatch(implementation.newLabelForIndex(enter + 1),
        implementation.newLabelForIndex(exit), implementation.newLabelForIndex(handler))
    return method
}

/** LINE 本体の helper が保持する書き込み用接続を返します。別ハンドルは開きません。 */
internal fun mainDatabaseGetter(owner: String, main: FieldReference, getter: Method): MutableMethod =
    generatedMethod(owner, MAIN_DATABASE, emptyList(), SQLITE_DATABASE, 2,
        BuilderInstruction11x(Opcode.RETURN_OBJECT, 0)).apply {
        addInstructions(0, """
            sget-object v0, ${smali(main)}
            invoke-static {v0}, $getter
            move-result-object v0
        """.trimIndent())
    }

internal fun ownMidGetter(owner: String, refs: ManualReadOwnMidReferences): MutableMethod =
    generatedMethod(owner, OWN_MID, emptyList(), STRING, 2, BuilderInstruction11x(Opcode.RETURN_OBJECT, 0)).apply {
        addInstructions(0, """
            iget-object v0, p0, ${smali(refs.account)}
            invoke-interface {v0}, ${smali(refs.profile)}
            move-result-object v0
            iget-object v0, v0, ${smali(refs.mid)}
        """.trimIndent())
    }

/** 一覧と下部タブのバッジへ、公式と同じ bus で CHAT_LIST と集計更新を順に通知します。 */
internal fun notifyChatListUpdated(owner: String, context: FieldReference, refs: ManualReadBusReferences,
    badgeEvent: FieldReference): MutableMethod =
    generatedMethod(owner, NOTIFY_CHAT_LIST, emptyList(), "V", 3, BuilderInstruction10x(Opcode.RETURN_VOID)).apply {
        addInstructions(0, """
            iget-object v0, p0, ${smali(context)}
            sget-object v1, ${smali(refs.serviceKey)}
            invoke-static {v0, v1}, ${smali(refs.service)}
            move-result-object v0
            check-cast v0, ${refs.busType}
            sget-object v1, ${smali(refs.chatList)}
            invoke-interface {v0, v1}, ${smali(refs.post)}
            sget-object v1, ${smali(badgeEvent)}
            invoke-interface {v0, v1}, ${smali(refs.post)}
        """.trimIndent())
    }

private fun generatedMethod(owner: String, name: String, parameters: List<String>, result: String, registers: Int,
    terminal: BuilderInstruction): MutableMethod {
    val implementation = MutableMethodImplementation(registers)
    implementation.addInstruction(terminal)
    return MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) },
        result, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, implementation))
}

/** v0:v1 は検索結果、v2 は比較用。parameter は v3..v7 に置き、元のメソッドの register は変更しません。 */
internal fun manualReadBridge(outbound: Method, getter: Method): MutableMethod {
    val implementation = MutableMethodImplementation(8)
    implementation.addInstruction(BuilderInstruction11x(Opcode.RETURN_WIDE, 0))
    val method = MutableMethod(ImmutableMethod(outbound.definingClass, MANUAL_READ,
        listOf("J", STRING, "Z").map { ImmutableMethodParameter(it, null, null) }, "J",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, implementation))
    method.addInstructions(0, """
        if-eqz p3, :none
        if-eqz p4, :selected
        invoke-virtual {p0, p3}, $getter
        move-result-wide v0
        cmp-long v2, v0, p1
        if-lez v2, :selected
        move-wide p1, v0
        :selected
        const-wide/16 v0, 0x0
        cmp-long v2, p1, v0
        if-lez v2, :none
        if-nez p4, :send
        invoke-virtual {p0, p1, p2, p3}, ${outbound.definingClass}->$CLEAR_STALE_FAILED_READ(J$STRING)V
        :send
        invoke-virtual/range {p0 .. p4}, $outbound
        if-nez p4, :done
        invoke-virtual {p0, p1, p2, p3}, ${outbound.definingClass}->$WAS_READ_SENT(J$STRING)Z
        move-result v0
        if-eqz v0, :none
        :done
        return-wide p1
        :none
        const-wide/16 v0, 0x0
    """.trimIndent())
    val body = checkNotNull(method.implementation)
    val handler = body.instructions.size
    method.addInstructions(handler, """
        move-exception v0
        const-wide/16 v0, 0x0
        return-wide v0
    """.trimIndent())
    body.addCatch(body.newLabelForIndex(0),
        body.newLabelForIndex(handler), body.newLabelForIndex(handler))
    return method
}
