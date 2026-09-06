package dev.utaa.linimal.patches.features.readreceipts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.utaa.linimal.patches.features.browser.externalBrowserChatTextLinkPatch
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus
import dev.utaa.linimal.patches.util.BOOLEAN
import dev.utaa.linimal.patches.util.HASH_MAP
import dev.utaa.linimal.patches.util.LONG
import dev.utaa.linimal.patches.util.OBJECT
import dev.utaa.linimal.patches.util.STRING
import dev.utaa.linimal.patches.util.VOID
import dev.utaa.linimal.patches.util.branchTargetAddress
import dev.utaa.linimal.patches.util.exceptionHandlerAddresses
import dev.utaa.linimal.patches.util.instructionAddress
import dev.utaa.linimal.patches.util.isDivertedInjectionIndex

private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"
private const val SHARED_PREFERENCES = "Landroid/content/SharedPreferences;"
private const val SHARED_PREFERENCES_EDITOR = "Landroid/content/SharedPreferences\$Editor;"
private const val TALK_SERVICE_CLIENT = "Ljp/naver/line/android/thrift/client/TalkServiceClient;"

/**
 * 難読化された型を受けるための wildcard。fingerprint の型比較は 1 文字の `L` を前方一致として
 * 扱うため、RxJava のように版ごとに名前が変わる型はこれで受けます。
 *
 * <p>26.11.0 では `Lip7/w;`(Single) / `Lip7/i;`(SingleCreate) / `Ldp7/a;`(SingleOnSubscribe) /
 * `Lap7/r;`(Scheduler) を直に書いていましたが、26.14.0 でそれぞれ `Lyw7/w;` / `Lyw7/j;` /
 * `Ltw7/a;` / `Lqw7/r;` へ変わりました。</p>
 */
private const val OBJECT_TYPE_PREFIX = "L"
private const val READ_RECEIPT_HOOKS =
    "Ldev/utaa/linimal/extension/features/readreceipts/ReadReceiptHooks;"
private const val SHOULD_SUPPRESS =
    "$READ_RECEIPT_HOOKS->shouldSuppressAutomatic(Ljava/lang/String;)Z"
private const val BEGIN_MANUAL =
    "$READ_RECEIPT_HOOKS->beginManualInvocation(Ljava/lang/String;)V"
private const val CLEAR_MANUAL =
    "$READ_RECEIPT_HOOKS->clearManualInvocation()V"
private const val REGISTER_SUPPLIER =
    "$READ_RECEIPT_HOOKS->registerSupplierFromCurrentInvocation(Ljava/lang/Object;Ljava/lang/String;)V"
private const val PREPARE_SUPPLIER =
    "$READ_RECEIPT_HOOKS->prepareSupplier(Ljava/lang/Object;Ljava/lang/String;)V"
private const val CLEAR_PREPARED =
    "$READ_RECEIPT_HOOKS->clearPreparedSupplier()V"

/** 命令 0 の if-eqz が飛び越す先。local update と chat-list Runnable の合流点です。 */
private const val OUTBOUND_GATE_MERGE_INDEX = 5

/**
 * 通常チャットの outbound read-receipt sender を、FAILED_CHAT_CHECKED 専用 queue・成功時の
 * SharedPreferences remove・RPC の全てを組み合わせて識別します。OpenChat/Square、Service Chat、
 * AI Character の経路はこの fingerprint に含めません。
 *
 * <p>このメソッドは「既読にする」処理そのもので、ローカル未読のクリアと既読位置の前進を実行して
 * から RPC を呼びます。`readWithoutReceiptLocalReadBlockPatch` が同じメソッドの先頭へ別の gate を
 * 注入するため、fingerprint は複製せずここを共有します。両者が同じメソッドへ当たることを定義として
 * 保証するためです。</p>
 */
internal val outboundGateFingerprint = Fingerprint(
    returnType = VOID,
    parameters = listOf(LONG, STRING, BOOLEAN),
    // MethodCallFilter は interface bridge の declaration variation を過度に狭めるため、ここでは
    // 全 reference/opcode 条件を custom predicate で同時に検証します。
    custom = { method, _ -> hasOutboundGateReferences(method) },
)

/** FAILED_CHAT_CHECKED を保持する encrypted preference wrapper だけを queue clear の対象にします。 */
private val failedChatCheckedStoreFingerprint = Fingerprint(
    returnType = VOID,
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(fieldAccess(name = "FAILED_CHAT_CHECKED", opcode = Opcode.SGET_OBJECT)),
    custom = { _, classDef -> classDef.fields.any { it.type == SHARED_PREFERENCES } },
)

/**
 * supplier factory を呼ぶ suspend 関数。トーク画面側の手動既読の呼び出し元です。
 *
 * <p>26.11.0 では continuation の `@DebugMetadata`
 * （`c = "…readreceipt.MainChatMarkAsReadExecutor"`, `m = "markAsRead"`）を anchor にしていました。
 * 26.14.0 ではこの関数が単純な tail-call へ最適化され、coroutine の state machine も continuation
 * クラスも生成されなくなったため anchor そのものが消えています。代わりに「supplier factory を呼ぶ
 * `(String, Continuation)Object`」という構造で特定します。26.11.0 の DEX に対して同じ条件を全 DEX
 * 走査すると、`@DebugMetadata` で特定していたのと同じ 1 件（`Lv11/a;->a`）へ解決します。</p>
 */
private val manualCallerFingerprint = Fingerprint(
    returnType = OBJECT,
    parameters = listOf(STRING, CONTINUATION),
)

/**
 * supplier factory。gate を持つクラスの中で、supplier を new して subscribe wrapper へ包み、
 * cached scheduler を挿して返す唯一のメソッドです。
 *
 * <p>26.11.0 では戻り値へ RxJava Single の難読化名 `Lip7/w;` を直に書いていましたが、26.14.0 で
 * `Lyw7/w;` へ変わりました。戻り値は wildcard で受け、実体は構造で識別します。</p>
 */
private val supplierFactoryFingerprint = Fingerprint(
    returnType = OBJECT_TYPE_PREFIX,
    parameters = listOf(STRING),
    custom = { method, classDef ->
        hasSupplierFactoryReferences(method) && classDef.methods.any(::hasOutboundGateReferences)
    },
)

/** RxJava の実装文字列を使い、難読化された scheduler field 名ではなく cached scheduler type を導きます。 */
private val cachedThreadSchedulerFingerprint = Fingerprint(
    strings = listOf("RxCachedThreadScheduler"),
)

private data class OutboundGateShape(
    val insertionIndex: Int,
    val queueField: FieldReference,
    val queueMapField: FieldReference,
    val queueStoreField: FieldReference,
    val storePreferencesField: FieldReference,
)

/**
 * manual caller への注入位置。いずれも「この index の直前へ挿入する」意味で保持し、後方から順に
 * 注入するため狭義単調増加であることを前提にします。
 *
 * <p>26.11.0 では coroutine の state machine が持つ CancellationException / `<any>` handler へ
 * cleanup を差し込んでいましたが、26.14.0 では handler ごと無くなったため、注入側で handler を
 * 自前に足します（[injectManualCaller]）。</p>
 */
private data class ManualCallerShape(
    val beginIndex: Int,
    val chatIdRegister: Int,
    val resultCleanupIndex: Int,
    val exceptionRegister: Int,
)

private data class SupplierFactoryShape(
    val supplierType: String,
    val onSubscribeType: String,
    val constructorIndex: Int,
    val supplierRegister: Int,
    val chatIdRegister: Int,
)

private fun hasOutboundGateReferences(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    fun indexOfCallAfter(startExclusive: Int, predicate: (MethodReference, Opcode) -> Boolean): Int =
        instructions.withIndex().firstOrNull { (index, instruction) ->
            index > startExclusive &&
                methodReference(instruction)?.let { reference -> predicate(reference, instruction.opcode) } == true
        }?.index ?: -1

    // 26.11.0 では `Y` / `Q0` / `j1` という難読化名で照合していましたが、26.14.0 でそれぞれ
    // `e0` / `U0` / `c1` へ変わりました。名前は版ごとに変わるため条件から落とし、引数と戻り値の形、
    // 非難読化の TalkServiceClient / SharedPreferences$Editor、そして参照の出現順で識別します。
    val localUpdate = indexOfCallAfter(-1) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE &&
            reference.parameterTypes.map(CharSequence::toString) == listOf(STRING) && reference.returnType == VOID
    }
    val runnable = indexOfCallAfter(localUpdate) { reference, opcode ->
        opcode == Opcode.INVOKE_VIRTUAL && reference.name == "run" &&
            reference.parameterTypes.isEmpty() && reference.returnType == VOID
    }
    val mapGet = indexOfCallAfter(runnable) { reference, opcode ->
        opcode == Opcode.INVOKE_VIRTUAL && methodMatches(reference, HASH_MAP, "get", listOf(OBJECT), OBJECT)
    }
    val mapPut = indexOfCallAfter(mapGet) { reference, opcode ->
        opcode == Opcode.INVOKE_VIRTUAL && methodMatches(reference, HASH_MAP, "put", listOf(OBJECT, OBJECT), OBJECT)
    }
    val localRead = indexOfCallAfter(mapPut) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE &&
            reference.parameterTypes.map(CharSequence::toString) == listOf(LONG, STRING) && reference.returnType == VOID
    }
    val rpc = indexOfCallAfter(localRead) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE && reference.definingClass == TALK_SERVICE_CLIENT &&
            reference.parameterTypes.map(CharSequence::toString) == listOf("I", STRING, STRING) &&
            reference.returnType == VOID
    }
    val remove = indexOfCallAfter(rpc) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE &&
            methodMatches(reference, SHARED_PREFERENCES_EDITOR, "remove", listOf(STRING), SHARED_PREFERENCES_EDITOR)
    }
    val failedSave = indexOfCallAfter(remove) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE &&
            methodMatches(reference, SHARED_PREFERENCES_EDITOR, "putLong", listOf(STRING, LONG), SHARED_PREFERENCES_EDITOR)
    }
    if (
        localUpdate < 0 || runnable < 0 || mapGet < 0 || mapPut < 0 || localRead < 0 ||
        rpc < 0 || remove < 0 || failedSave < 0
    ) {
        return false
    }
    // ローカル未読のクリアと既読位置の前進は、mainchatdata の同じ interface が持ちます。
    // 難読化名を条件から落とした分、この同一性で対象を絞り直します。
    return methodReference(instructions[localUpdate])?.definingClass ==
        methodReference(instructions[localRead])?.definingClass
}

/** [method] が [target] を呼ぶかどうか。難読化名ではなく解決済みの参照そのもので突き合わせます。 */
private fun callsMethod(method: Method, target: Method): Boolean =
    method.implementation?.instructions?.any { instruction ->
        methodReference(instruction)?.sameMethod(target) == true
    } == true

/**
 * supplier factory の形かどうか。
 *
 * <p>26.11.0 は `Lip7/i;-><init>(Ldp7/a;)V` と `Lap7/b;->o(Lap7/r;)Lip7/w;` を難読化名のまま
 * 書いていましたが、26.14.0 で全て名前が変わりました。「先頭で supplier を new する」「引数 1 つの
 * object を取る constructor で包む」「引数 1 つでこのメソッドの戻り値型を返す呼び出しで scheduler を
 * 挿す」という構造だけを条件にします。</p>
 */
private fun hasSupplierFactoryReferences(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    if (instructions.firstOrNull()?.opcode != Opcode.NEW_INSTANCE) {
        return false
    }
    val wrapsSupplier = instructions.any { instruction ->
        instruction.opcode == Opcode.INVOKE_DIRECT &&
            methodReference(instruction)?.let { reference ->
                reference.name == "<init>" && reference.returnType == VOID &&
                    reference.parameterTypes.singleOrNull()?.toString()?.startsWith(OBJECT_TYPE_PREFIX) == true
            } == true
    }
    val schedules = instructions.any { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            methodReference(instruction)?.let { reference ->
                reference.parameterTypes.size == 1 && reference.returnType == method.returnType
            } == true
    }
    return wrapsSupplier && schedules
}

/**
 * local update と chat-list Runnable を元どおり実行した直後に gate を置きます。suppression 時は
 * Q0、RPC、失敗保存より前で終了し、同じ FAILED_CHAT_CHECKED queue の map/prefs だけを消去します。
 *
 * <p>注入は合流点そのものではなく、その直後の location に置きます。合流点は先頭の if-eqz の分岐先
 * であり、dexlib2 の注入では Label が既存 location に残るため、第 4 引数 Z が false の経路だけが
 * gate を飛び越して送信まで到達してしまいます。</p>
 */
val readReceiptOutboundGatePatch = bytecodePatch(
    name = "通常チャットの自動既読",
    description = "通常チャットの既読送信を、実行時設定で止められるようにします。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(externalBrowserChatTextLinkPatch)

    execute {
        val gateMatches = outboundGateFingerprint.matchAllOrNull().orEmpty()
        val storeMatches = failedChatCheckedStoreFingerprint.matchAllOrNull().orEmpty()
        if (gateMatches.size != 1 || storeMatches.size != 1) {
            if (gateMatches.size == 1) {
                // queue clear を一意に構成できない限り、gate 単体も適用済みとして扱わない。
                recordUnsafeFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_GATE),
                    expectedTargetCount = 1,
                    actualTargetCount = 1,
                    reason = "ReadReceiptFailedQueueStoreNotUnique",
                )
            } else {
                recordFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_GATE),
                    expectedTargetCount = 1,
                    actualTargetCount = gateMatches.size,
                    reason = "ReadReceiptOutboundGateNotUnique",
                )
            }
            if (storeMatches.size == 1) {
                // outbound gate へ注入しないため、store anchor だけを適用済みとして記録しない。
                recordUnsafeFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_PENDING_QUEUE_CLEAR),
                    expectedTargetCount = 1,
                    actualTargetCount = 1,
                    reason = "ReadReceiptOutboundGateNotUnique",
                )
            } else {
                recordFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_PENDING_QUEUE_CLEAR),
                    expectedTargetCount = 1,
                    actualTargetCount = storeMatches.size,
                    reason = "ReadReceiptFailedQueueStoreNotUnique",
                )
            }
            return@execute
        }

        val shape = outboundGateShape(gateMatches.single(), storeMatches.single().originalClassDef.type)
        if (shape == null) {
            recordUnsafeFeatureStatus(
                listOf(
                    PatchId.READ_RECEIPTS_MAIN_CHAT_GATE,
                    PatchId.READ_RECEIPTS_MAIN_CHAT_PENDING_QUEUE_CLEAR,
                ),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadReceiptOutboundInstructionShapeMismatch",
            )
            return@execute
        }

        injectOutboundGate(gateMatches.single().method, shape)
        recordFeatureStatus(
            listOf(
                PatchId.READ_RECEIPTS_MAIN_CHAT_GATE,
                PatchId.READ_RECEIPTS_MAIN_CHAT_PENDING_QUEUE_CLEAR,
            ),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "ReadReceiptOutboundGateAndQueueClearInjected",
        )
    }
}

/** 手動既読 executor の caller thread で supplier factory 呼び出しを囲みます。 */
val readReceiptManualCallerPatch = bytecodePatch(
    name = "手動既読の呼び出し元",
    description = "LINE 自身の手動既読操作を識別できるよう、呼び出し元スレッドへ印を付けます。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(readReceiptOutboundGatePatch)

    execute {
        val factoryMatches = supplierFactoryFingerprint.matchAllOrNull().orEmpty()
        // supplier factory を呼ぶ suspend 関数だけが手動既読の呼び出し元です。factory 自体が
        // 一意に決まらない限り候補を絞れないため、その場合は空集合として扱います。
        val manualMatches = factoryMatches.singleOrNull()?.let { factory ->
            manualCallerFingerprint.matchAllOrNull().orEmpty()
                .filter { candidate -> callsMethod(candidate.originalMethod, factory.method) }
        }.orEmpty()
        // manualMatches が空でないのは factory が一意に決まったときだけなので、件数だけを見れば
        // supplier chain の一意性も同時に満たされています。
        if (manualMatches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_MANUAL_CALLER),
                expectedTargetCount = 1,
                actualTargetCount = manualMatches.size,
                reason = "ReadReceiptManualCallerNotUnique",
            )
            return@execute
        }

        val factory = factoryMatches.single()
        val callerShape = manualCallerShape(manualMatches.single(), factory)
        if (callerShape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_MANUAL_CALLER),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadReceiptManualCallerInstructionShapeMismatch",
            )
            return@execute
        }

        injectManualCaller(manualMatches.single().method, callerShape)
        recordFeatureStatus(
            listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_MANUAL_CALLER),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "ReadReceiptManualCallerCleanupInjected",
        )
    }
}

/** supplier identity へ caller thread の one-shot origin を移します。 */
val readReceiptSupplierRegistrationPatch = bytecodePatch(
    name = "手動既読の受け渡し",
    description = "手動既読の印を、非同期処理へ引き継ぐための識別子として登録します。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(readReceiptManualCallerPatch)

    execute {
        val factories = supplierFactoryFingerprint.matchAllOrNull().orEmpty()
        val schedulerTypes = cachedThreadSchedulerFingerprint.matchAllOrNull().orEmpty()
            .map { it.originalClassDef.type }
            .toSet()
        if (factories.size != 1 || schedulerTypes.isEmpty()) {
            if (factories.size == 1) {
                // cached scheduler を確認できない限り、factory anchor だけを適用済みとして記録しない。
                recordUnsafeFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_REGISTRATION),
                    expectedTargetCount = 1,
                    actualTargetCount = 1,
                    reason = "ReadReceiptSupplierSchedulerUnavailable",
                )
            } else {
                recordFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_REGISTRATION),
                    expectedTargetCount = 1,
                    actualTargetCount = factories.size,
                    reason = "ReadReceiptSupplierFactoryNotUnique",
                )
            }
            return@execute
        }

        val shape = supplierFactoryShape(factories.single(), schedulerTypes, requireRegistration = false)
        if (shape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_REGISTRATION),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadReceiptSupplierFactoryInstructionShapeMismatch",
            )
            return@execute
        }

        factories.single().method.addInstructions(
            shape.constructorIndex + 1,
            "invoke-static { v${shape.supplierRegister}, v${shape.chatIdRegister} }, $REGISTER_SUPPLIER",
        )
        recordFeatureStatus(
            listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_REGISTRATION),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "ReadReceiptSupplierRegistrationInjected",
        )
    }
}

/** worker thread で supplier identity を allowance へ変換し、return/exception 全出口で cleanup します。 */
val readReceiptSupplierPreparationPatch = bytecodePatch(
    name = "手動既読の送信許可",
    description = "非同期処理側で手動既読の識別子を送信許可へ変換し、処理の終了時に破棄します。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) {
            PatchAvailability.REQUIRED
        } else {
            PatchAvailability.UNAVAILABLE
        }
    }
    dependsOn(readReceiptSupplierRegistrationPatch)

    execute {
        val factories = supplierFactoryFingerprint.matchAllOrNull().orEmpty()
        val schedulerTypes = cachedThreadSchedulerFingerprint.matchAllOrNull().orEmpty()
            .map { it.originalClassDef.type }
            .toSet()
        if (factories.size != 1 || schedulerTypes.isEmpty()) {
            if (factories.size == 1) {
                recordUnsafeFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_PREPARATION),
                    expectedTargetCount = 1,
                    actualTargetCount = 1,
                    reason = "ReadReceiptSupplierPreparationSchedulerUnavailable",
                )
            } else {
                recordFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_PREPARATION),
                    expectedTargetCount = 1,
                    actualTargetCount = factories.size,
                    reason = "ReadReceiptSupplierPreparationFactoryUnavailable",
                )
            }
            return@execute
        }

        val factoryShape = supplierFactoryShape(factories.single(), schedulerTypes, requireRegistration = true)
        if (factoryShape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_PREPARATION),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadReceiptSupplierRegistrationChainMismatch",
            )
            return@execute
        }

        val workerMatches = supplierWorkerFingerprint(factoryShape.supplierType, factoryShape.onSubscribeType)
            .matchAllOrNull().orEmpty()
        if (workerMatches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_PREPARATION),
                expectedTargetCount = 1,
                actualTargetCount = workerMatches.size,
                reason = "ReadReceiptSupplierWorkerNotUnique",
            )
            return@execute
        }

        val workerShape = supplierWorkerShape(workerMatches.single(), factories.single())
        if (workerShape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_PREPARATION),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadReceiptSupplierWorkerInstructionShapeMismatch",
            )
            return@execute
        }

        injectSupplierPreparation(workerMatches.single().method, workerShape)
        recordFeatureStatus(
            listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_PREPARATION),
            expectedTargetCount = 1,
            actualTargetCount = 1,
            reason = "ReadReceiptSupplierPreparationCleanupInjected",
        )
    }
}

private fun outboundGateShape(match: Match, failedStoreType: String): OutboundGateShape? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions
    val parameterStart = implementation.registerCount - 5 // this + wide J + String + boolean
    val localMark = parameterStart + 4
    val thisRegister = parameterStart
    val readPointLow = parameterStart + 1
    val chatId = parameterStart + 3
    if (
        implementation.registerCount != 10 ||
        parameterStart != 5 ||
        instructions.size < 76 ||
        !isOneRegister(instructions[0], Opcode.IF_EQZ, localMark) ||
        !isIgetObject(instructions[1], localMark, thisRegister) ||
        // 26.11.0 の `Y` は 26.14.0 で `e0` へ変わりました。名前は条件から落とします。
        !isInvoke(instructions[2], Opcode.INVOKE_INTERFACE, listOf(localMark, chatId), null, listOf(STRING), VOID) ||
        !isIgetObject(instructions[3], localMark, thisRegister) ||
        !isInvoke(instructions[4], Opcode.INVOKE_VIRTUAL, listOf(localMark), "run", emptyList(), VOID) ||
        !isIgetObject(instructions[5], localMark, thisRegister)
    ) {
        return null
    }

    val queueField = fieldReference(instructions[5]) ?: return null
    val queueType = queueField.type
    val queueInit = methodReference(instructions[7])
    val queueMapField = fieldReference(instructions[8])
    val mapGet = methodReference(instructions[9])
    val mapPut = methodReference(instructions[18])
    if (
        queueField.definingClass != match.originalClassDef.type ||
        !isOneRegister(instructions[6], Opcode.MONITOR_ENTER, localMark) ||
        queueInit == null ||
        queueInit.definingClass != queueType ||
        queueInit.parameterTypes.isNotEmpty() ||
        queueInit.returnType != VOID ||
        queueMapField == null ||
        queueMapField.definingClass != queueType ||
        queueMapField.type != HASH_MAP ||
        !isTwoRegister(instructions[8], Opcode.IGET_OBJECT, 0, localMark) ||
        mapGet == null ||
        !methodMatches(mapGet, HASH_MAP, "get", listOf(OBJECT), OBJECT) ||
        !isInvokeRegisters(instructions[9], listOf(0, chatId)) ||
        mapPut == null ||
        !methodMatches(mapPut, HASH_MAP, "put", listOf(OBJECT, OBJECT), OBJECT) ||
        !isInvokeRegisters(instructions[18], listOf(3, chatId, 0)) ||
        !hasOutboundTail(instructions, readPointLow, chatId, thisRegister)
    ) {
        return null
    }

    val removeIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let {
            methodMatches(it, SHARED_PREFERENCES_EDITOR, "remove", listOf(STRING), SHARED_PREFERENCES_EDITOR)
        } == true
    }
    if (removeIndex < 4) {
        return null
    }
    val queueStoreField = fieldReference(instructions[removeIndex - 4])
    val storePreferencesField = fieldReference(instructions[removeIndex - 3])
    if (
        !isIgetObject(instructions[removeIndex - 4], 5, localMark) ||
        !isIgetObject(instructions[removeIndex - 3], 5, 5) ||
        queueStoreField == null ||
        queueStoreField.definingClass != queueType ||
        queueStoreField.type != failedStoreType ||
        storePreferencesField == null ||
        storePreferencesField.definingClass != failedStoreType ||
        storePreferencesField.type != SHARED_PREFERENCES ||
        !isInvokeRegisters(instructions[removeIndex], listOf(5, chatId)) ||
        !isOneRegister(instructions[removeIndex + 1], Opcode.MOVE_RESULT_OBJECT, 5) ||
        !isInvoke(instructions[removeIndex + 2], Opcode.INVOKE_INTERFACE, listOf(5), "apply", emptyList(), VOID)
    ) {
        return null
    }

    // 注入位置が既存の分岐先や例外 handler の先頭と一致すると、その経路だけが gate を飛び越します。
    // mutable 側の label 配置ではなく、transform 前 Match の instruction/exception table で判定します。
    val originalImplementation = match.originalMethod.implementation ?: return null
    val originalInstructions = originalImplementation.instructions.toList()
    val insertionIndex = outboundGateInsertionIndex(
        originalInstructions,
        exceptionHandlerAddresses(originalImplementation),
    ) ?: return null

    return OutboundGateShape(
        insertionIndex = insertionIndex,
        queueField = queueField,
        queueMapField = queueMapField,
        queueStoreField = queueStoreField,
        storePreferencesField = storePreferencesField,
    )
}

private fun hasOutboundTail(
    instructions: List<BuilderInstruction>,
    readPointLow: Int,
    chatId: Int,
    thisRegister: Int,
): Boolean {
    // 26.11.0 の `Q0` / `j1` は 26.14.0 で `U0` / `c1` へ変わりました。名前は条件から落とし、
    // opcode と引数・戻り値の形、そして非難読化の TalkServiceClient で識別します。
    val q0Index = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_INTERFACE &&
            methodReference(instruction)?.let { method ->
                method.parameterTypes.map(CharSequence::toString) == listOf(LONG, STRING) &&
                    method.returnType == VOID
            } == true
    }
    val rpcIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_INTERFACE &&
            methodReference(instruction)?.let { method ->
                method.definingClass == TALK_SERVICE_CLIENT &&
                    method.parameterTypes.map(CharSequence::toString) == listOf("I", STRING, STRING) &&
                    method.returnType == VOID
            } == true
    }
    val putLongIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { method ->
            methodMatches(method, SHARED_PREFERENCES_EDITOR, "putLong", listOf(STRING, LONG), SHARED_PREFERENCES_EDITOR)
        } == true
    }
    return q0Index > 18 &&
        rpcIndex > q0Index &&
        putLongIndex > rpcIndex &&
        isInvokeRegisters(instructions[q0Index], listOf(9, readPointLow, readPointLow + 1, chatId)) &&
        isIgetObject(instructions[q0Index - 1], 9, thisRegister) &&
        isInvokeRegisters(instructions[rpcIndex], listOf(0, 9, chatId, 1))
}

/**
 * 合流点の直後へ注入するため、合流点が v9 に読み込んだ queue は gate の scratch 用途で潰れます。
 * fail-open で元の flow へ戻る `:originalQueue` で同じ iget-object を読み直し、続く monitor-enter が
 * 元どおり queue を掴めるようにします。
 */
private fun injectOutboundGate(method: MutableMethod, shape: OutboundGateShape) {
    val queueField = fieldSmali(shape.queueField)
    val queueMapField = fieldSmali(shape.queueMapField)
    val queueStoreField = fieldSmali(shape.queueStoreField)
    val storePreferencesField = fieldSmali(shape.storePreferencesField)
    method.addInstructionsWithLabels(
        shape.insertionIndex,
        """
            invoke-static { v8 }, $SHOULD_SUPPRESS
            move-result v9
            if-eqz v9, :originalQueue
            iget-object v9, v5, $queueField
            monitor-enter v9
            invoke-virtual { v9 }, ${shape.queueField.type}->a()V
            iget-object v0, v9, $queueMapField
            invoke-virtual { v0, v8 }, Ljava/util/HashMap;->remove(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            iget-object v0, v9, $queueStoreField
            iget-object v0, v0, $storePreferencesField
            invoke-interface { v0 }, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences${'$'}Editor;
            move-result-object v0
            invoke-interface { v0, v8 }, Landroid/content/SharedPreferences${'$'}Editor;->remove(Ljava/lang/String;)Landroid/content/SharedPreferences${'$'}Editor;
            move-result-object v0
            invoke-interface { v0 }, Landroid/content/SharedPreferences${'$'}Editor;->apply()V
            monitor-exit v9
            return-void
            :clearFailure
            move-exception v0
            monitor-exit v9
            goto :originalQueue
            :originalQueue
            iget-object v9, v5, $queueField
        """.trimIndent(),
    )

    // suppress branch の queue clear 自体が異常なら monitor を解放して、元の queue/RPC flow へ fail-open。
    val implementation = checkNotNull(method.implementation)
    implementation.addCatch(
        implementation.newLabelForIndex(shape.insertionIndex + 5),
        implementation.newLabelForIndex(shape.insertionIndex + 16),
        implementation.newLabelForIndex(shape.insertionIndex + 18),
    )
}

/**
 * 手動既読の呼び出し元の形を検証します。
 *
 * <p>26.11.0 の呼び出し元は coroutine の state machine で、`invokeSuspend` 用の continuation と
 * CancellationException / `<any>` の handler を持っていました。26.14.0 では suspend 関数が
 * `factory(chatId)` を await するだけの tail-call へ最適化され、state machine も handler も
 * 生成されません。そのため handler を探す代わりに、注入側で handler を足せることを前提に
 * 「try block がまだ 1 つも無い」ことを条件にします。</p>
 */
private fun manualCallerShape(match: Match, factory: Match): ManualCallerShape? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions
    // this + String + Continuation の 3 parameter。
    val parameterStart = implementation.registerCount - 3
    if (parameterStart < 0) {
        return null
    }
    val chatIdRegister = parameterStart + 1
    val factoryCallIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { it.sameMethod(factory.method) } == true
    }
    val resultCleanupIndex = factoryCallIndex + 2
    if (
        factoryCallIndex < 1 ||
        // 既存の handler があると、その先頭が注入位置と重なって cleanup を飛び越す余地が残ります。
        implementation.tryBlocks.isNotEmpty() ||
        instructions[factoryCallIndex].opcode != Opcode.INVOKE_VIRTUAL ||
        invokeRegisters(instructions[factoryCallIndex]).lastOrNull() != chatIdRegister ||
        !isOneRegister(instructions.getOrNull(factoryCallIndex + 1), Opcode.MOVE_RESULT_OBJECT, parameterStart) ||
        resultCleanupIndex !in instructions.indices
    ) {
        return null
    }

    // begin と正常完了 cleanup は、全経路が注入を通ることを求めます。mutable 側の label 配置では
    // なく、transform 前 Match の instruction/exception table で判定します。
    val originalImplementation = match.originalMethod.implementation ?: return null
    val originalInstructions = originalImplementation.instructions.toList()
    val handlerAddresses = exceptionHandlerAddresses(originalImplementation)
    if (
        isDivertedInjectionIndex(originalInstructions, factoryCallIndex, handlerAddresses) ||
        isDivertedInjectionIndex(originalInstructions, resultCleanupIndex, handlerAddresses)
    ) {
        return null
    }

    return ManualCallerShape(
        beginIndex = factoryCallIndex,
        chatIdRegister = chatIdRegister,
        resultCleanupIndex = resultCleanupIndex,
        // 例外を受ける register。move-exception の直後に throw するだけなので、元の値を潰しても
        // その block の外へ影響しません。v0 は 4bit / 8bit のどの形式でも表現できます。
        exceptionRegister = 0,
    )
}

/**
 * 後方から注入して、先行する注入位置の index がずれないようにします。
 *
 * <p>26.11.0 は既存の CancellationException / `<any>` handler の直後へ cleanup を差し込んで
 * いましたが、26.14.0 の呼び出し元には handler がありません。supplier 構築が途中で失敗しても
 * caller thread の印が残らないよう、factory 呼び出しを覆う handler を自前で足します。</p>
 */
private fun injectManualCaller(method: MutableMethod, shape: ManualCallerShape) {
    method.addInstructions(shape.resultCleanupIndex, "invoke-static { }, $CLEAR_MANUAL")
    method.addInstructions(shape.beginIndex, "invoke-static { v${shape.chatIdRegister} }, $BEGIN_MANUAL")

    // 注入後の並び。begin が 1 命令、result cleanup が 1 命令ぶん後続をずらします。
    val tryStartIndex = shape.beginIndex + 1
    val tryEndIndex = shape.resultCleanupIndex + 2
    val implementation = checkNotNull(method.implementation)
    val handlerIndex = implementation.instructions.size
    method.addInstructions(
        handlerIndex,
        """
            move-exception v${shape.exceptionRegister}
            invoke-static { }, $CLEAR_MANUAL
            throw v${shape.exceptionRegister}
        """.trimIndent(),
    )
    implementation.addCatch(
        implementation.newLabelForIndex(tryStartIndex),
        implementation.newLabelForIndex(tryEndIndex),
        implementation.newLabelForIndex(handlerIndex),
    )
}

/**
 * supplier factory の命令列を検証し、注入に必要な参照と register を取り出します。
 *
 * <p>26.11.0 は supplier ごとに専用クラスがあり、constructor は
 * `<init>(<factory>, String)` で register も `{supplier, this, chatId}` に固定でした。26.14.0 では
 * R8 が別機能の lambda と 1 クラスへ畳み込み、`<init>(Object, Serializable, int)` と判別子つきに
 * なっています。型と並びを決め打ちせず「supplier を receiver に取り chatId を渡す `<init>`」として
 * 探し、続く wrapper / scheduler / return は constructor からの相対位置で検証します。</p>
 */
private fun supplierFactoryShape(
    match: Match,
    cachedSchedulerTypes: Set<String>,
    requireRegistration: Boolean,
): SupplierFactoryShape? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions
    // this + String の 2 parameter。
    val parameterStart = implementation.registerCount - 2
    val chatIdRegister = parameterStart + 1
    if (parameterStart < 1) {
        return null
    }
    val supplierNew = instructions.firstOrNull() ?: return null
    val supplierType = typeReference(supplierNew) ?: return null
    val supplierRegister = (supplierNew as? OneRegisterInstruction)?.registerA ?: return null
    if (supplierNew.opcode != Opcode.NEW_INSTANCE || supplierRegister != 0) {
        return null
    }

    val constructorIndex = instructions.indices.firstOrNull { index ->
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.INVOKE_DIRECT) return@firstOrNull false
        val reference = methodReference(instruction) ?: return@firstOrNull false
        val registers = invokeRegisters(instruction)
        reference.definingClass == supplierType &&
            reference.name == "<init>" &&
            reference.returnType == VOID &&
            registers.firstOrNull() == supplierRegister &&
            chatIdRegister in registers.drop(1)
    } ?: return null

    val registrationIndex = constructorIndex + 1
    val registrationPresent = isInvoke(
        instructions.getOrNull(registrationIndex),
        Opcode.INVOKE_STATIC,
        listOf(supplierRegister, chatIdRegister),
        "registerSupplierFromCurrentInvocation",
        listOf(OBJECT, STRING),
        VOID,
        definingClass = READ_RECEIPT_HOOKS,
    )
    if (requireRegistration != registrationPresent) {
        return null
    }
    val wrapperNewIndex = registrationIndex + if (registrationPresent) 1 else 0
    val wrapperNew = instructions.getOrNull(wrapperNewIndex)
    val wrapperType = typeReference(wrapperNew) ?: return null
    val wrapperConstructor = instructions.getOrNull(wrapperNewIndex + 1)
    val onSubscribeType = methodReference(wrapperConstructor)
        ?.parameterTypes?.singleOrNull()?.toString() ?: return null
    val schedulerRead = instructions.getOrNull(wrapperNewIndex + 2)
    val schedule = instructions.getOrNull(wrapperNewIndex + 3)
    val scheduleReference = methodReference(schedule)
    val result = instructions.getOrNull(wrapperNewIndex + 4)
    val returned = instructions.getOrNull(wrapperNewIndex + 5)
    val schedulerField = fieldReference(schedulerRead)
    if (
        !isOneRegister(wrapperNew, Opcode.NEW_INSTANCE, parameterStart) ||
        !isInvoke(
            wrapperConstructor,
            Opcode.INVOKE_DIRECT,
            listOf(parameterStart, supplierRegister),
            "<init>",
            listOf(onSubscribeType),
            VOID,
            definingClass = wrapperType,
        ) ||
        schedulerField == null ||
        schedulerField.type !in cachedSchedulerTypes ||
        !isOneRegister(schedulerRead, Opcode.SGET_OBJECT, chatIdRegister) ||
        schedule?.opcode != Opcode.INVOKE_VIRTUAL ||
        scheduleReference == null ||
        scheduleReference.parameterTypes.size != 1 ||
        scheduleReference.returnType != method.returnType ||
        !isInvokeRegisters(schedule, listOf(parameterStart, chatIdRegister)) ||
        !isOneRegister(result, Opcode.MOVE_RESULT_OBJECT, parameterStart) ||
        !isOneRegister(returned, Opcode.RETURN_OBJECT, parameterStart) ||
        // return が末尾であることまで見て、factory がこの chain だけで構成されることを保証します。
        wrapperNewIndex + 5 != instructions.size - 1
    ) {
        return null
    }
    return SupplierFactoryShape(
        supplierType = supplierType,
        onSubscribeType = onSubscribeType,
        constructorIndex = constructorIndex,
        supplierRegister = supplierRegister,
        chatIdRegister = chatIdRegister,
    )
}

/**
 * supplier worker へ注入する prefix の命令数。owner の判定 3 命令、chatId の読み出しと prepare の
 * 3 命令、合流点の `nop` 1 命令です。cleanup の注入位置を prefix ぶんずらすために使います。
 */
internal const val SUPPLIER_PREPARATION_PREFIX_LENGTH = 7

/** prefix でだけ使う一時 register。`run()` の入口では local register はすべて未初期化です。 */
private const val SCRATCH_OWNER_REGISTER = 0
private const val SCRATCH_FLAG_REGISTER = 1

internal data class SupplierWorkerShape(
    val factoryType: String,
    val ownerField: FieldReference,
    val chatIdField: FieldReference,
    val thisRegister: Int,
    /** read point が 0 のときの早期 return へ落ちる `goto` の index。**注入前**の並びの index です。 */
    val earlyCleanupIndex: Int,
    /** 既読送信後の `return-void` の index。**注入前**の並びの index です。 */
    val normalCleanupIndex: Int,
)

private fun supplierWorkerFingerprint(supplierType: String, onSubscribeType: String) = Fingerprint(
    definingClass = supplierType,
    name = "run",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        methodCall(parameters = listOf(STRING), returnType = LONG, opcode = Opcode.INVOKE_VIRTUAL),
        methodCall(parameters = listOf(LONG, STRING, BOOLEAN), returnType = VOID, opcode = Opcode.INVOKE_VIRTUAL),
    ),
    custom = { _, classDef -> classDef.interfaces.contains(onSubscribeType) },
)

/**
 * supplier worker の命令列を検証し、注入に必要な参照と register を取り出します。
 *
 * <p>26.11.0 の supplier は read receipt 専用のクラスで、`run()` は 11 命令の一本道でした。
 * 26.14.0 では R8 が無関係な機能の lambda と 1 クラスへ畳み込み、`run()` は先頭で int の判別子を
 * 読んで `packed-switch` で分岐する形になっています。既読の経路は case の側にあり、`this` は
 * 命令 2 で owner に潰されるため、`prepareSupplier(this, chatId)` は先頭でしか呼べません。
 * 先頭は畳み込まれた別機能も通るので、owner が factory かどうかで自分の経路だけに限定します。</p>
 */
private fun supplierWorkerShape(match: Match, factory: Match): SupplierWorkerShape? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions
    val factoryType = factory.originalClassDef.type
    // run()V は引数なしなので、parameter register は `this` の 1 つだけです。
    val thisRegister = implementation.registerCount - 1

    val readPointIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            methodReference(instruction)?.let {
                it.definingClass == factoryType &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(STRING) && it.returnType == LONG
            } == true
    }
    val outboundIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            methodReference(instruction)?.let {
                it.definingClass == factoryType &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(LONG, STRING, BOOLEAN) &&
                    it.returnType == VOID
            } == true
    }
    if (
        implementation.tryBlocks.isNotEmpty() ||
        // prefix の scratch は parameter より前から取ります。iget-object / instance-of は 4bit の
        // register しか取れないため、`this` も v15 までであることを求めます。
        thisRegister <= SCRATCH_FLAG_REGISTER ||
        thisRegister > 15 ||
        readPointIndex < 3 ||
        outboundIndex != readPointIndex + 7
    ) {
        return null
    }

    val discriminatorRead = instructions[0]
    val chatIdRead = instructions[1]
    val ownerRead = instructions[2]
    val switch = instructions[3]
    val discriminatorRegister = (discriminatorRead as? OneRegisterInstruction)?.registerA ?: return null
    val chatIdRegister = (chatIdRead as? OneRegisterInstruction)?.registerA ?: return null
    val chatIdField = fieldReference(chatIdRead) ?: return null
    val ownerField = fieldReference(ownerRead) ?: return null
    if (
        !isTwoRegister(discriminatorRead, Opcode.IGET, discriminatorRegister, thisRegister) ||
        !isIgetObject(chatIdRead, chatIdRegister, thisRegister) ||
        !isIgetObject(ownerRead, thisRegister, thisRegister) ||
        !isOneRegister(switch, Opcode.PACKED_SWITCH, discriminatorRegister) ||
        chatIdField.definingClass != method.definingClass ||
        ownerField.definingClass != method.definingClass ||
        !chatIdField.type.startsWith(OBJECT_TYPE_PREFIX) ||
        !ownerField.type.startsWith(OBJECT_TYPE_PREFIX)
    ) {
        return null
    }

    // 既読の case。owner と chatId を実型へ落としてから read point を引き、0 でなければ送信します。
    val caseIndex = readPointIndex - 2
    val readPointRegister = (instructions[readPointIndex + 1] as? OneRegisterInstruction)?.registerA ?: return null
    val flagRegister = (instructions[readPointIndex + 6] as? OneRegisterInstruction)?.registerA ?: return null
    if (
        !isOneRegister(instructions[caseIndex], Opcode.CHECK_CAST, thisRegister) ||
        typeReference(instructions[caseIndex]) != factoryType ||
        !isOneRegister(instructions[caseIndex + 1], Opcode.CHECK_CAST, chatIdRegister) ||
        typeReference(instructions[caseIndex + 1]) != STRING ||
        !isInvokeRegisters(instructions[readPointIndex], listOf(thisRegister, chatIdRegister)) ||
        instructions[readPointIndex + 1].opcode != Opcode.MOVE_RESULT_WIDE ||
        instructions[readPointIndex + 2].opcode != Opcode.CONST_WIDE_16 ||
        instructions[readPointIndex + 3].opcode != Opcode.CMP_LONG ||
        instructions[readPointIndex + 4].opcode != Opcode.IF_NEZ ||
        instructions[readPointIndex + 5].opcode != Opcode.GOTO ||
        instructions[readPointIndex + 6].opcode != Opcode.CONST_4 ||
        !isInvokeRegisters(
            instructions[outboundIndex],
            listOf(thisRegister, readPointRegister, readPointRegister + 1, chatIdRegister, flagRegister),
        ) ||
        instructions.getOrNull(outboundIndex + 1)?.opcode != Opcode.RETURN_VOID
    ) {
        return null
    }

    val earlyCleanupIndex = readPointIndex + 5
    val normalCleanupIndex = outboundIndex + 1

    // prefix と早期 return の cleanup は、その経路が必ず通らなければ one-shot が残留します。
    // 正常終了の cleanup を置く `return-void` は早期 return からの `goto` 先でもありますが、
    // その経路は手前の cleanup を通るため、ここでは分岐先かどうかを問いません。
    // mutable 側の label 配置ではなく、transform 前 Match の instruction/exception table で判定します。
    val originalImplementation = match.originalMethod.implementation ?: return null
    val originalInstructions = originalImplementation.instructions.toList()
    val handlerAddresses = exceptionHandlerAddresses(originalImplementation)
    if (
        isDivertedInjectionIndex(originalInstructions, 0, handlerAddresses) ||
        isDivertedInjectionIndex(originalInstructions, earlyCleanupIndex, handlerAddresses)
    ) {
        return null
    }
    return SupplierWorkerShape(
        factoryType = factoryType,
        ownerField = ownerField,
        chatIdField = chatIdField,
        thisRegister = thisRegister,
        earlyCleanupIndex = earlyCleanupIndex,
        normalCleanupIndex = normalCleanupIndex,
    )
}

/**
 * 後方から注入して、先行する注入位置の index がずれないようにします。prefix は最後に入れるため、
 * cleanup の index は注入前の並びのまま使えます。
 */
private fun injectSupplierPreparation(method: MutableMethod, shape: SupplierWorkerShape) {
    val ownerField = fieldSmali(shape.ownerField)
    val chatIdField = fieldSmali(shape.chatIdField)
    val thisRegister = shape.thisRegister

    method.addInstructions(shape.normalCleanupIndex, "invoke-static { }, $CLEAR_PREPARED")
    method.addInstructions(shape.earlyCleanupIndex, "invoke-static { }, $CLEAR_PREPARED")

    // `this` は元の命令 2 で owner に潰されるため、prepare はその手前でしか呼べません。先頭は
    // 同じクラスへ畳み込まれた別機能の lambda も通るので、owner が factory の場合だけ prepare し、
    // それ以外は 3 命令で元の経路へ抜けます。v0 / v1 は入口では未初期化の local です。
    method.addInstructionsWithLabels(
        0,
        """
            iget-object v$SCRATCH_OWNER_REGISTER, v$thisRegister, $ownerField
            instance-of v$SCRATCH_FLAG_REGISTER, v$SCRATCH_OWNER_REGISTER, ${shape.factoryType}
            if-eqz v$SCRATCH_FLAG_REGISTER, :rrPrepareDone
            iget-object v$SCRATCH_OWNER_REGISTER, v$thisRegister, $chatIdField
            check-cast v$SCRATCH_OWNER_REGISTER, $STRING
            invoke-static { v$thisRegister, v$SCRATCH_OWNER_REGISTER }, $PREPARE_SUPPLIER
            :rrPrepareDone
            nop
        """.trimIndent(),
    )

    val implementation = checkNotNull(method.implementation)
    val handlerIndex = implementation.instructions.size
    method.addInstructions(
        handlerIndex,
        """
            move-exception v$SCRATCH_OWNER_REGISTER
            invoke-static { }, $CLEAR_PREPARED
            throw v$SCRATCH_OWNER_REGISTER
        """.trimIndent(),
    )
    // 元の supplier に handler はありません。prefix の直後から既読送信後の return-void までを覆い、
    // どの経路でも one-shot を残しません。末尾の packed-switch payload は命令ではないため含めません。
    implementation.addCatch(
        implementation.newLabelForIndex(SUPPLIER_PREPARATION_PREFIX_LENGTH),
        implementation.newLabelForIndex(shape.normalCleanupIndex + SUPPLIER_PREPARATION_PREFIX_LENGTH + 3),
        implementation.newLabelForIndex(handlerIndex),
    )
}

private fun methodReference(instruction: Instruction?): MethodReference? =
    (instruction as? ReferenceInstruction)?.reference as? MethodReference

private fun fieldReference(instruction: Instruction?): FieldReference? =
    (instruction as? ReferenceInstruction)?.reference as? FieldReference

private fun typeReference(instruction: Instruction?): String? =
    ((instruction as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.TypeReference)?.type

private fun fieldSmali(field: FieldReference): String = "${field.definingClass}->${field.name}:${field.type}"

private fun methodMatches(
    reference: MethodReference,
    definingClass: String,
    name: String,
    parameters: List<String>,
    returnType: String,
): Boolean = reference.definingClass == definingClass &&
    reference.name == name &&
    reference.parameterTypes.map(CharSequence::toString) == parameters &&
    reference.returnType == returnType

private fun MethodReference.sameMethod(other: Method): Boolean =
    definingClass == other.definingClass &&
        name == other.name &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString) &&
        returnType == other.returnType

private fun isOneRegister(instruction: Instruction?, opcode: Opcode, register: Int): Boolean =
    instruction?.opcode == opcode && (instruction as? OneRegisterInstruction)?.registerA == register

private fun isTwoRegister(
    instruction: Instruction?,
    opcode: Opcode,
    first: Int,
    second: Int,
): Boolean = instruction?.opcode == opcode &&
    (instruction as? TwoRegisterInstruction)?.let { it.registerA == first && it.registerB == second } == true

private fun isIgetObject(instruction: Instruction?, destination: Int, receiver: Int): Boolean =
    isTwoRegister(instruction, Opcode.IGET_OBJECT, destination, receiver)

/** [name] に null を渡すと、難読化名を条件から外して opcode / 引数・戻り値・register だけで照合します。 */
private fun isInvoke(
    instruction: Instruction?,
    opcode: Opcode,
    registers: List<Int>,
    name: String?,
    parameters: List<String>,
    returnType: String,
    definingClass: String? = null,
): Boolean {
    val reference = methodReference(instruction) ?: return false
    return instruction?.opcode == opcode &&
        (definingClass == null || reference.definingClass == definingClass) &&
        (name == null || reference.name == name) &&
        reference.parameterTypes.map(CharSequence::toString) == parameters &&
        reference.returnType == returnType &&
        isInvokeRegisters(instruction, registers)
}

/** invoke が実際に並べている引数 register。35c 形式は使わない slot が 0 を返すため切り詰めます。 */
private fun invokeRegisters(instruction: Instruction?): List<Int> {
    val invoke = instruction as? FiveRegisterInstruction ?: return emptyList()
    return listOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF, invoke.registerG)
        .take(invoke.registerCount)
}

private fun isInvokeRegisters(instruction: Instruction?, expected: List<Int>): Boolean =
    instruction is FiveRegisterInstruction && invokeRegisters(instruction) == expected

/**
 * outbound gate の注入位置。命令 0 の if-eqz は local update と chat-list Runnable を飛び越して queue
 * 取得へ分岐するため、その分岐先そのものへ注入すると第 4 引数 Z が false の経路だけが gate を飛び越し、
 * 抑制されないまま送信へ到達します。分岐先の直後なら fall-through と分岐の双方が gate を通ります。
 */
internal fun outboundGateInsertionIndex(
    instructions: List<Instruction>,
    handlerAddresses: Set<Int>,
): Int? {
    if (instructions.getOrNull(0)?.opcode != Opcode.IF_EQZ) {
        return null
    }
    val mergeAddress = branchTargetAddress(instructions, 0) ?: return null
    val mergeIndex = instructions.indices
        .firstOrNull { index -> instructionAddress(instructions, index) == mergeAddress }
        ?: return null
    if (mergeIndex != OUTBOUND_GATE_MERGE_INDEX) {
        return null
    }
    val insertionIndex = mergeIndex + 1
    return insertionIndex.takeUnless { isDivertedInjectionIndex(instructions, it, handlerAddresses) }
}
