package dev.utaa.linimal.patches.features.readreceipts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import dev.utaa.linimal.patches.features.browser.externalBrowserChatTextLinkPatch
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val VOID = "V"
private const val BOOLEAN = "Z"
private const val LONG = "J"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val CONTINUATION = "Lkotlin/coroutines/Continuation;"
private const val HASH_MAP = "Ljava/util/HashMap;"
private const val SHARED_PREFERENCES = "Landroid/content/SharedPreferences;"
private const val SHARED_PREFERENCES_EDITOR = "Landroid/content/SharedPreferences\$Editor;"
private const val TALK_SERVICE_CLIENT = "Ljp/naver/line/android/thrift/client/TalkServiceClient;"
private const val RX_SINGLE = "Lip7/w;"
private const val RX_SINGLE_CREATE = "Lip7/i;"
private const val RX_SINGLE_ON_SUBSCRIBE = "Ldp7/a;"
private const val RX_SCHEDULER = "Lap7/r;"
private const val CANCELLATION_EXCEPTION = "Ljava/util/concurrent/CancellationException;"
private const val DEBUG_METADATA = "Llb8/e;"
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
 */
private val outboundGateFingerprint = Fingerprint(
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

/** MainChatMarkAsReadExecutor.kt の DebugMetadata を持つ coroutine continuation。 */
private val mainChatMarkAsReadMetadataFingerprint = Fingerprint(
    name = "invokeSuspend",
    returnType = OBJECT,
    parameters = listOf(OBJECT),
    custom = { _, classDef ->
        classDef.annotations.any(::isMainChatMarkAsReadMetadata)
    },
)

/** source metadata owner から導く、manual caller の signature/coroutine/Rx chain。 */
private val manualCallerFingerprint = Fingerprint(
    returnType = OBJECT,
    parameters = listOf(STRING, CONTINUATION),
)

/** q33.e.e 相当の supplier factory。constructor -> Rx Single -> cached scheduler chain を必須にします。 */
private val supplierFactoryFingerprint = Fingerprint(
    returnType = RX_SINGLE,
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
 */
private data class ManualCallerShape(
    val beginIndex: Int,
    val resultCleanupIndex: Int,
    val genericCleanupIndex: Int,
    val cancellationCleanupIndex: Int,
)

private data class SupplierFactoryShape(
    val supplierType: String,
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

    val localUpdate = indexOfCallAfter(-1) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE && reference.name == "Y" &&
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
        opcode == Opcode.INVOKE_INTERFACE && reference.name == "Q0" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf(LONG, STRING) && reference.returnType == VOID
    }
    val rpc = indexOfCallAfter(localRead) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE &&
            methodMatches(reference, TALK_SERVICE_CLIENT, "j1", listOf("I", STRING, STRING), VOID)
    }
    val remove = indexOfCallAfter(rpc) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE &&
            methodMatches(reference, SHARED_PREFERENCES_EDITOR, "remove", listOf(STRING), SHARED_PREFERENCES_EDITOR)
    }
    val failedSave = indexOfCallAfter(remove) { reference, opcode ->
        opcode == Opcode.INVOKE_INTERFACE &&
            methodMatches(reference, SHARED_PREFERENCES_EDITOR, "putLong", listOf(STRING, LONG), SHARED_PREFERENCES_EDITOR)
    }
    return localUpdate >= 0 && runnable >= 0 && mapGet >= 0 && mapPut >= 0 && localRead >= 0 &&
        rpc >= 0 && remove >= 0 && failedSave >= 0
}

private fun hasManualCallerReferences(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    return instructions.any { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL && reference.parameterTypes.map(CharSequence::toString) == listOf(STRING) &&
                reference.returnType == RX_SINGLE
        } == true
    } && instructions.any { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_STATIC &&
                reference.parameterTypes.map(CharSequence::toString) == listOf("Lap7/b;", "Llb8/c;") && reference.returnType == OBJECT
        } == true
    } && instructions.any { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_STATIC &&
                methodMatches(reference, "Lkotlin/ResultKt;", "throwOnFailure", listOf(OBJECT), VOID)
        } == true
    }
}

private fun hasSupplierFactoryReferences(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    return instructions.any { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_DIRECT &&
                methodMatches(reference, RX_SINGLE_CREATE, "<init>", listOf(RX_SINGLE_ON_SUBSCRIBE), VOID)
        } == true
    } && instructions.any { instruction ->
        methodReference(instruction)?.let { reference ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                methodMatches(reference, "Lap7/b;", "o", listOf(RX_SCHEDULER), RX_SINGLE)
        } == true
    }
}

/**
 * local update と chat-list Runnable を元どおり実行した直後に gate を置きます。suppression 時は
 * Q0、RPC、失敗保存より前で終了し、同じ FAILED_CHAT_CHECKED queue の map/prefs だけを消去します。
 *
 * <p>注入は合流点そのものではなく、その直後の location に置きます。合流点は先頭の if-eqz の分岐先
 * であり、dexlib2 の注入では Label が既存 location に残るため、第 4 引数 Z が false の経路だけが
 * gate を飛び越して送信まで到達してしまいます。</p>
 */
val readReceiptOutboundGatePatch = bytecodePatch {
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

/** MainChatMarkAsReadExecutor の caller thread で supplier factory 呼び出しを囲みます。 */
val readReceiptManualCallerPatch = bytecodePatch {
    dependsOn(readReceiptOutboundGatePatch)

    execute {
        val metadataMatches = mainChatMarkAsReadMetadataFingerprint.matchAllOrNull().orEmpty()
        val metadataOwners = metadataMatches
            .map { metadata ->
                val nestedType = metadata.originalClassDef.type
                nestedType.substringBeforeLast('$', missingDelimiterValue = "").takeIf { it.isNotEmpty() }?.plus(";")
            }
            .filterNotNull()
            .toSet()
        val factoryMatches = supplierFactoryFingerprint.matchAllOrNull().orEmpty()
        val manualMatches = manualCallerFingerprint.matchAllOrNull().orEmpty()
            .filter { it.originalClassDef.type in metadataOwners }
        if (manualMatches.size != 1 || metadataOwners.size != 1 || factoryMatches.size != 1) {
            if (manualMatches.size == 1) {
                // metadata / supplier chain が一意でなければ、caller anchor だけを適用済みとしない。
                recordUnsafeFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_MANUAL_CALLER),
                    expectedTargetCount = 1,
                    actualTargetCount = 1,
                    reason = "ReadReceiptManualCallerDependencyMismatch",
                )
            } else {
                recordFeatureStatus(
                    listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_MANUAL_CALLER),
                    expectedTargetCount = 1,
                    actualTargetCount = manualMatches.size,
                    reason = "ReadReceiptManualCallerNotUnique",
                )
            }
            return@execute
        }

        val factory = factoryMatches.single()
        val callerShape = manualCallerShape(manualMatches.single(), factory)
        if (callerShape == null) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_MANUAL_CALLER),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadReceiptManualCallerStateMachineMismatch",
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
val readReceiptSupplierRegistrationPatch = bytecodePatch {
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
val readReceiptSupplierPreparationPatch = bytecodePatch {
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

        val workerMatches = supplierWorkerFingerprint(factoryShape.supplierType).matchAllOrNull().orEmpty()
        if (workerMatches.size != 1) {
            recordFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_PREPARATION),
                expectedTargetCount = 1,
                actualTargetCount = workerMatches.size,
                reason = "ReadReceiptSupplierWorkerNotUnique",
            )
            return@execute
        }

        if (!supplierWorkerShape(workerMatches.single(), factories.single())) {
            recordUnsafeFeatureStatus(
                listOf(PatchId.READ_RECEIPTS_MAIN_CHAT_SUPPLIER_PREPARATION),
                expectedTargetCount = 1,
                actualTargetCount = 1,
                reason = "ReadReceiptSupplierWorkerInstructionShapeMismatch",
            )
            return@execute
        }

        injectSupplierPreparation(workerMatches.single().method)
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
        !isInvoke(instructions[2], Opcode.INVOKE_INTERFACE, listOf(localMark, chatId), "Y", listOf(STRING), VOID) ||
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
    val q0Index = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { method ->
            method.name == "Q0" && method.parameterTypes == listOf(LONG, STRING) && method.returnType == VOID
        } == true
    }
    val rpcIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { method ->
            methodMatches(method, TALK_SERVICE_CLIENT, "j1", listOf("I", STRING, STRING), VOID)
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

private fun isMainChatMarkAsReadMetadata(annotation: Annotation): Boolean {
    if (annotation.type != DEBUG_METADATA) {
        return false
    }
    val strings = annotation.elements.associate { element ->
        element.name to (element.value as? StringEncodedValue)?.value
    }
    return strings["f"] == "MainChatMarkAsReadExecutor.kt" && strings["m"] == "markAsRead"
}

private fun manualCallerShape(match: Match, factory: Match): ManualCallerShape? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions
    val factoryCallIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { it.sameMethod(factory.method) } == true
    }
    if (
        !hasManualCallerReferences(match.originalMethod) ||
        implementation.registerCount != 7 ||
        instructions.size < 41 ||
        factoryCallIndex < 1 ||
        !isIgetObject(instructions[factoryCallIndex - 1], 4, 4) ||
        !isInvokeRegisters(instructions[factoryCallIndex], listOf(4, 5)) ||
        !isOneRegister(instructions[factoryCallIndex + 1], Opcode.MOVE_RESULT_OBJECT, 4) ||
        !isTwoRegister(instructions[factoryCallIndex + 2], Opcode.IPUT, 3, 0) ||
        !isInvoke(instructions[factoryCallIndex + 3], Opcode.INVOKE_STATIC, listOf(4, 0), "a", listOf("Lap7/b;", "Llb8/c;"), OBJECT)
    ) {
        return null
    }

    // Mutable implementation の label placement ではなく、transform 前 Match の exception table を検証します。
    val originalImplementation = match.originalMethod.implementation ?: return null
    val originalInstructions = originalImplementation.instructions.toList()
    val originalAddresses = instructionCodeAddresses(originalInstructions)
    val factoryAddress = originalAddresses.getOrNull(factoryCallIndex) ?: return null
    val coveringBlock = originalImplementation.tryBlocks.singleOrNull { block ->
        block.startCodeAddress <= factoryAddress && factoryAddress < block.startCodeAddress + block.codeUnitCount
    } ?: return null
    val genericHandlerAddress = coveringBlock.exceptionHandlers
        .singleOrNull { it.exceptionType == null }
        ?.handlerCodeAddress ?: return null
    val cancellationHandlerAddress = coveringBlock.exceptionHandlers
        .singleOrNull { it.exceptionType == CANCELLATION_EXCEPTION }
        ?.handlerCodeAddress ?: return null
    val genericHandlerIndex = originalAddresses.indexOf(genericHandlerAddress)
    val cancellationHandlerIndex = originalAddresses.indexOf(cancellationHandlerAddress)
    if (genericHandlerIndex < 0 || cancellationHandlerIndex < 0 ||
        !isOneRegister(originalInstructions[genericHandlerIndex], Opcode.CONST_4, 3) ||
        !isOneRegister(originalInstructions[cancellationHandlerIndex], Opcode.MOVE_EXCEPTION, 4)
    ) {
        return null
    }

    // begin と正常完了 cleanup は、全経路が注入を通ることを求めます。
    val handlerAddresses = exceptionHandlerAddresses(originalImplementation)
    val resultCleanupIndex = factoryCallIndex + 2
    if (
        isDivertedInjectionIndex(originalInstructions, factoryCallIndex, handlerAddresses) ||
        isDivertedInjectionIndex(originalInstructions, resultCleanupIndex, handlerAddresses)
    ) {
        return null
    }

    // 例外時 cleanup は handler 先頭ではなく直後へ置きます。先頭は Label ごと後ろへずれるため、
    // 先頭へ注入すると例外経路が cleanup を飛び越し、手動既読の ThreadLocal が残留します。
    val genericCleanupIndex = exceptionHandlerCleanupIndex(originalInstructions, genericHandlerIndex)
        ?: return null
    val cancellationCleanupIndex = exceptionHandlerCleanupIndex(originalInstructions, cancellationHandlerIndex)
        ?: return null

    val injectionIndices = listOf(factoryCallIndex, resultCleanupIndex, genericCleanupIndex, cancellationCleanupIndex)
    // 後方から注入して先行 index を保つため、狭義単調増加でなければ shape を受け付けません。
    if (injectionIndices != injectionIndices.sorted() || injectionIndices.toSet().size != injectionIndices.size) {
        return null
    }
    return ManualCallerShape(
        beginIndex = factoryCallIndex,
        resultCleanupIndex = resultCleanupIndex,
        genericCleanupIndex = genericCleanupIndex,
        cancellationCleanupIndex = cancellationCleanupIndex,
    )
}

/**
 * 後方から注入して、先行する注入位置の index がずれないようにします。CancellationException と
 * `<any>` の cleanup はいずれも handler 先頭ではなくその直後へ入り、例外経路でも必ず手動既読の
 * ThreadLocal を解放します。
 */
private fun injectManualCaller(method: MutableMethod, shape: ManualCallerShape) {
    method.addInstructions(shape.cancellationCleanupIndex, "invoke-static { }, $CLEAR_MANUAL")
    method.addInstructions(shape.genericCleanupIndex, "invoke-static { }, $CLEAR_MANUAL")
    method.addInstructions(shape.resultCleanupIndex, "invoke-static { }, $CLEAR_MANUAL")
    method.addInstructions(shape.beginIndex, "invoke-static { v5 }, $BEGIN_MANUAL")
}

private fun supplierFactoryShape(
    match: Match,
    cachedSchedulerTypes: Set<String>,
    requireRegistration: Boolean,
): SupplierFactoryShape? {
    val method = match.method
    val implementation = method.implementation ?: return null
    val instructions = implementation.instructions
    val parameterStart = implementation.registerCount - 2
    if (implementation.registerCount != 3 || parameterStart != 1 || instructions.size !in 8..9) {
        return null
    }
    val supplierNew = instructions[0]
    val supplierType = typeReference(supplierNew) ?: return null
    val supplierRegister = (supplierNew as? OneRegisterInstruction)?.registerA ?: return null
    val constructor = instructions.getOrNull(1)
    val constructorReference = methodReference(constructor)
    if (
        supplierNew.opcode != Opcode.NEW_INSTANCE ||
        supplierRegister != 0 ||
        constructorReference == null ||
        constructorReference.definingClass != supplierType ||
        constructorReference.name != "<init>" ||
        constructorReference.parameterTypes != listOf(match.originalClassDef.type, STRING) ||
        constructorReference.returnType != VOID ||
        !isInvokeRegisters(constructor, listOf(supplierRegister, parameterStart, parameterStart + 1))
    ) {
        return null
    }

    val registrationIndex = 2
    val registrationPresent = isInvoke(
        instructions.getOrNull(registrationIndex),
        Opcode.INVOKE_STATIC,
        listOf(supplierRegister, parameterStart + 1),
        "registerSupplierFromCurrentInvocation",
        listOf(OBJECT, STRING),
        VOID,
        definingClass = READ_RECEIPT_HOOKS,
    )
    if (requireRegistration != registrationPresent) {
        return null
    }
    val wrapperNewIndex = if (registrationPresent) 3 else 2
    val wrapperNew = instructions.getOrNull(wrapperNewIndex)
    val wrapperConstructor = instructions.getOrNull(wrapperNewIndex + 1)
    val schedulerRead = instructions.getOrNull(wrapperNewIndex + 2)
    val schedule = instructions.getOrNull(wrapperNewIndex + 3)
    val result = instructions.getOrNull(wrapperNewIndex + 4)
    val returned = instructions.getOrNull(wrapperNewIndex + 5)
    val schedulerField = fieldReference(schedulerRead)
    if (
        wrapperNew?.opcode != Opcode.NEW_INSTANCE ||
        !isOneRegister(wrapperNew, Opcode.NEW_INSTANCE, 1) ||
        !isInvoke(
            wrapperConstructor,
            Opcode.INVOKE_DIRECT,
            listOf(1, supplierRegister),
            "<init>",
            listOf(RX_SINGLE_ON_SUBSCRIBE),
            VOID,
            definingClass = RX_SINGLE_CREATE,
        ) ||
        schedulerField == null ||
        schedulerField.type !in cachedSchedulerTypes ||
        !isOneRegister(schedulerRead, Opcode.SGET_OBJECT, parameterStart + 1) ||
        !isInvoke(
            schedule,
            Opcode.INVOKE_VIRTUAL,
            listOf(1, parameterStart + 1),
            "o",
            listOf(RX_SCHEDULER),
            RX_SINGLE,
            definingClass = "Lap7/b;",
        ) ||
        !isOneRegister(result, Opcode.MOVE_RESULT_OBJECT, 1) ||
        !isOneRegister(returned, Opcode.RETURN_OBJECT, 1)
    ) {
        return null
    }
    return SupplierFactoryShape(
        supplierType = supplierType,
        constructorIndex = 1,
        supplierRegister = supplierRegister,
        chatIdRegister = parameterStart + 1,
    )
}

private fun supplierWorkerFingerprint(supplierType: String) = Fingerprint(
    definingClass = supplierType,
    name = "run",
    returnType = VOID,
    parameters = emptyList(),
    filters = listOf(
        methodCall(parameters = listOf(STRING), returnType = LONG, opcode = Opcode.INVOKE_VIRTUAL),
        methodCall(parameters = listOf(LONG, STRING, BOOLEAN), returnType = VOID, opcode = Opcode.INVOKE_VIRTUAL),
    ),
    custom = { _, classDef -> classDef.interfaces.contains(RX_SINGLE_ON_SUBSCRIBE) },
)

private fun supplierWorkerShape(match: Match, factory: Match): Boolean {
    val method = match.method
    val implementation = method.implementation ?: return false
    val instructions = implementation.instructions
    val readPointCallIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let { it.definingClass == factory.originalClassDef.type && it.parameterTypes == listOf(STRING) && it.returnType == LONG } == true
    }
    val outboundCallIndex = instructions.indexOfFirst { instruction ->
        methodReference(instruction)?.let {
            it.definingClass == factory.originalClassDef.type &&
                it.parameterTypes == listOf(LONG, STRING, BOOLEAN) && it.returnType == VOID
        } == true
    }
    if (
        implementation.registerCount != 6 ||
        implementation.tryBlocks.isNotEmpty() ||
        instructions.size != 11 ||
        readPointCallIndex != 2 ||
        outboundCallIndex != 9 ||
        !isIgetObject(instructions[0], 0, 5) ||
        !isIgetObject(instructions[1], 5, 5) ||
        !isInvokeRegisters(instructions[2], listOf(0, 5)) ||
        !isOneRegister(instructions[3], Opcode.MOVE_RESULT_WIDE, 1) ||
        instructions[7].opcode != Opcode.RETURN_VOID ||
        !isOneRegister(instructions[8], Opcode.CONST_4, 3) ||
        !isInvokeRegisters(instructions[9], listOf(0, 1, 2, 5, 3)) ||
        instructions[10].opcode != Opcode.RETURN_VOID ||
        instructions.any { instructionUsesRegister(it, 4) }
    ) {
        return false
    }
    return true
}

private fun injectSupplierPreparation(method: MutableMethod) {
    // v4 is proven unused. Preserve p0 before the original method repurposes v5 for chatId.
    method.addInstructions(0, "move-object v4, p0")

    // Original index 7 is the readPoint == 0 exit after the one-instruction prefix was inserted.
    method.addInstructions(8, "invoke-static { }, $CLEAR_PREPARED")
    // Original d() invocation is now index 11. prepare must be immediately before it.
    method.addInstructions(11, "invoke-static { v4, v5 }, $PREPARE_SUPPLIER")
    // The d() normal completion falls through its original return; clear before that return.
    method.addInstructions(13, "invoke-static { }, $CLEAR_PREPARED")

    val implementation = checkNotNull(method.implementation)
    val handlerIndex = implementation.instructions.size
    method.addInstructions(
        handlerIndex,
        """
            move-exception v0
            invoke-static { }, $CLEAR_PREPARED
            throw v0
        """.trimIndent(),
    )
    // The original supplier has no handlers. Cover point calculation, zero exit, prepare, d(), and normal cleanup.
    implementation.addCatch(
        implementation.newLabelForIndex(1),
        implementation.newLabelForIndex(handlerIndex),
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

private fun isInvoke(
    instruction: Instruction?,
    opcode: Opcode,
    registers: List<Int>,
    name: String,
    parameters: List<String>,
    returnType: String,
    definingClass: String? = null,
): Boolean {
    val reference = methodReference(instruction) ?: return false
    return instruction?.opcode == opcode &&
        (definingClass == null || reference.definingClass == definingClass) &&
        reference.name == name &&
        reference.parameterTypes.map(CharSequence::toString) == parameters &&
        reference.returnType == returnType &&
        isInvokeRegisters(instruction, registers)
}

private fun isInvokeRegisters(instruction: Instruction?, expected: List<Int>): Boolean {
    val invoke = instruction as? FiveRegisterInstruction ?: return false
    val actual = listOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF, invoke.registerG)
        .take(invoke.registerCount)
    return actual == expected
}

private fun instructionCodeAddresses(instructions: List<Instruction>): List<Int> {
    var address = 0
    return instructions.map { instruction ->
        address.also { address += instruction.codeUnits }
    }
}

private fun instructionUsesRegister(instruction: Instruction, register: Int): Boolean = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC,
        instruction.registerD,
        instruction.registerE,
        instruction.registerF,
        instruction.registerG,
    ).take(instruction.registerCount).contains(register)
    is TwoRegisterInstruction -> instruction.registerA == register || instruction.registerB == register
    is OneRegisterInstruction -> instruction.registerA == register
    else -> false
}

private fun exceptionHandlerAddresses(implementation: MethodImplementation): Set<Int> =
    implementation.tryBlocks
        .flatMap { block -> block.exceptionHandlers }
        .map { handler -> handler.handlerCodeAddress }
        .toSet()

private fun instructionAddress(instructions: List<Instruction>, index: Int): Int =
    instructions.take(index).sumOf { it.codeUnits }

private fun branchTargetAddress(instructions: List<Instruction>, index: Int): Int? {
    val branch = instructions.getOrNull(index) as? OffsetInstruction ?: return null
    return instructionAddress(instructions, index) + branch.codeOffset
}

/**
 * dexlib2 の `addInstructions(index, ...)` は新しい MethodLocation を挿入し、既存 location は Label を
 * 保持したまま後ろへずれます。そのため注入位置が既存の分岐先や例外 handler の先頭と一致すると、
 * その経路だけが注入コードを飛び越します。全経路が通る必要のある注入では、この位置を拒否します。
 */
internal fun isDivertedInjectionIndex(
    instructions: List<Instruction>,
    index: Int,
    handlerAddresses: Set<Int> = emptySet(),
): Boolean {
    if (index !in instructions.indices) {
        return true
    }
    val address = instructionAddress(instructions, index)
    return address in handlerAddresses ||
        instructions.indices.any { branchTargetAddress(instructions, it) == address }
}

/** 直後の命令へ制御が落ちる命令かどうか。落ちない命令の直後は注入しても実行されません。 */
internal fun fallsThrough(instruction: Instruction): Boolean = when (instruction.opcode) {
    Opcode.GOTO,
    Opcode.GOTO_16,
    Opcode.GOTO_32,
    Opcode.RETURN,
    Opcode.RETURN_VOID,
    Opcode.RETURN_VOID_BARRIER,
    Opcode.RETURN_VOID_NO_BARRIER,
    Opcode.RETURN_WIDE,
    Opcode.RETURN_OBJECT,
    Opcode.THROW,
    -> false
    else -> true
}

/**
 * 例外 handler の cleanup 注入位置。handler ラベルは既存 location に残るため、handler 先頭へ注入すると
 * 例外経路が cleanup を飛び越します。よって先頭ではなく、その直後の location を注入位置にします。
 * 先頭が fall-through しない命令なら直後へ置いても実行されないため、その shape は受け付けません。
 */
internal fun exceptionHandlerCleanupIndex(instructions: List<Instruction>, handlerIndex: Int): Int? {
    val head = instructions.getOrNull(handlerIndex) ?: return null
    if (!fallsThrough(head)) {
        return null
    }
    return (handlerIndex + 1).takeIf { it in instructions.indices }
}

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
