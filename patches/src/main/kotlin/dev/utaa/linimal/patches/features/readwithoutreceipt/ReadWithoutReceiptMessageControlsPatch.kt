package dev.utaa.linimal.patches.features.readwithoutreceipt

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import dev.utaa.linimal.patches.shared.Constants
import dev.utaa.linimal.patches.status.PatchId
import dev.utaa.linimal.patches.status.recordFeatureStatus
import dev.utaa.linimal.patches.status.recordUnappliedFeatureStatus
import dev.utaa.linimal.patches.status.recordUnsafeFeatureStatus

private const val CONTROLS = "Ldev/utaa/linimal/extension/features/readwithoutreceipt/ReadWithoutReceiptMessageControls;"
private const val BIND_CONTROLS = "linimalBindReadControls"
private const val VIEW = "Landroid/view/View;"
private const val STRING = "Ljava/lang/String;"

/** 形式別 balloon ではなく、通常・システム両方が必ず通る共通行 bind を観測します。 */
val readWithoutReceiptMessageControlsPatch = bytecodePatch(
    name = "既読なし閲覧のメッセージ別既読操作",
    description = "受信した通常メッセージの下に、そこまでを既読にする操作を追加します。",
) {
    compatibleWith(Constants.LINE_COMPATIBILITY)
    availability { _, architecture ->
        if (architecture == ApkArchitecture.ARM64_V8A) PatchAvailability.REQUIRED else PatchAvailability.UNAVAILABLE
    }
    dependsOn(readWithoutReceiptManualReadPatch)
    execute {
        val ids = listOf(PatchId.READ_WITHOUT_RECEIPT_MESSAGE_CONTROLS,
            PatchId.NOTIFICATION_READ_WITHOUT_RECEIPT_MESSAGE_CONTROLS)
        val models = Fingerprint(name = "toString", parameters = emptyList(), returnType = STRING,
            strings = listOf("ChatMessageCommonViewData(chatId=", ", localMessageId=", ", serverMessageId=",
                ", isNormalMessage=", ", isOtherUserMessage=", ", isMyMessage=")).matchAllOrNull().orEmpty()
        val binders = Fingerprint(returnType = "V",
            strings = listOf("ContentView(Landroidx/compose/runtime/Composer;I)V"),
            custom = { method, _ -> method.parameterTypes.size == 8 && method.parameterTypes[0] == "I" &&
                method.parameterTypes[4] == "I" && method.parameterTypes[7] == "I" &&
                AccessFlags.FINAL.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) },
        ).matchAllOrNull().orEmpty()
        if (models.size != 1 || binders.size != 1) {
            val reason = if (models.size != 1) "ReadControlsModelNotUnique" else "ReadControlsBinderNotUnique"
            recordUnappliedFeatureStatus(ids, 2, models.size + binders.size, reason)
            return@execute
        }
        val model = models.single()
        val binder = binders.single()
        // 実 APK の constructor は引数順に全 final field を保存する。名前ではなく型と保存順を照合する。
        val constructors = model.originalClassDef.methods.filter { it.name == "<init>" }
        val fields = constructors.singleOrNull()?.implementation?.instructions?.toList().orEmpty().mapNotNull {
            if (it.opcode != Opcode.IPUT && it.opcode != Opcode.IPUT_OBJECT &&
                it.opcode != Opcode.IPUT_WIDE && it.opcode != Opcode.IPUT_BOOLEAN) null
            else (it as? ReferenceInstruction)?.reference as? FieldReference
        }.filter { it.definingClass == model.originalClassDef.type }.distinctBy { it.name }.toList()
        val prefix = listOf(STRING, "J", "J", STRING, STRING, "L", "Z", "L",
            "Ljava/lang/Long;", "Z", "Z", "L", "Z", "Z", "Z")
        val shape = fields.size == 27 && fields.take(15).map { it.type }.withIndex().all { (index, type) ->
            // contentType・表示型は fingerprint で決まった model 内の参照型でよく、難読化名では固定しない。
            if (index == 5 || index == 7 || index == 11) type.startsWith("L") else type == prefix[index]
        }
        val itemType = binder.originalMethod.parameterTypes[3].toString()
        val getters = binder.originalMethod.implementation?.instructions?.toList().orEmpty().mapNotNull {
            (it as? ReferenceInstruction)?.reference as? MethodReference
        }.filter { it.definingClass == itemType && it.parameterTypes.isEmpty() &&
            it.returnType == model.originalClassDef.type }.distinctBy { it.name }.toList()
        val roots = binder.originalClassDef.methods.filter { method ->
            method.parameterTypes.isEmpty() && method.returnType == VIEW &&
                method.implementation?.instructions?.map { it.opcode }?.toList() == listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT)
        }
        // native checker は最初の未読 ID との完全一致を判定し、adapter の String getter は
        // ChatData.read_up の入室時スナップショットを返す。同じ field を参照する契約で解決する。
        val adapterType = binder.originalMethod.parameterTypes[1].toString()
        val checkerType = fields.getOrNull(21)?.type
        val checkers = if (checkerType == null) emptyList() else Fingerprint(
            definingClass = checkerType, parameters = listOf("J"), returnType = "Z",
        ).matchAllOrNull().orEmpty()
        val markerImplementations = if (checkers.size != 1) emptyList() else Fingerprint(
            name = checkers.single().originalMethod.name, parameters = listOf("J"), returnType = "Z",
            custom = { method, clazz -> clazz.interfaces.contains(adapterType) &&
                method.implementation?.instructions?.any {
                    val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                    field?.type == STRING && field.definingClass == clazz.type
                } == true },
        ).matchAllOrNull().orEmpty()
        val markerOwner = markerImplementations.singleOrNull()?.originalClassDef
        val baselineFields = markerImplementations.singleOrNull()?.originalMethod?.implementation
            ?.instructions?.toList().orEmpty().mapNotNull {
                (it as? ReferenceInstruction)?.reference as? FieldReference
            }.filter { it.type == STRING && it.definingClass == markerOwner?.type }.distinct()
        val baselineGetters = markerOwner?.methods?.toList().orEmpty().filter { method ->
            baselineFields.singleOrNull()?.let { isNativeReadPositionGetter(method, it) } == true
        }
        val firstUnreadField = markerImplementations.singleOrNull()?.originalMethod?.let(::nativeFirstUnreadField)
        val readableFirstUnread = markerOwner?.fields?.any {
            it.name == firstUnreadField?.name && it.type == firstUnreadField?.type &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        } == true
        val implementation = binder.originalMethod.implementation
        if (!shape || getters.size != 1 || roots.size != 1 || implementation == null ||
            implementation.registerCount < 9 || binder.originalClassDef.methods.any { it.name == BIND_CONTROLS } ||
            binder.originalMethod.parameterTypes.any { it == "J" || it == "D" } ||
            checkers.size != 1 || markerImplementations.size != 1 || baselineGetters.size != 1 ||
            firstUnreadField == null || !readableFirstUnread) {
            recordUnsafeFeatureStatus(ids, 2, 2, "ReadControlsShapeMismatch")
            return@execute
        }
        val parameters = listOf(binder.originalClassDef.type) + binder.originalMethod.parameterTypes.map { it.toString() }
        val generatedImplementation = MutableMethodImplementation(18)
        generatedImplementation.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        val bridge = MutableMethod(ImmutableMethod(binder.originalClassDef.type, BIND_CONTROLS,
            parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, generatedImplementation))
        val getter = getters.single()
        val root = roots.single()
        val markerType = checkNotNull(markerOwner).type
        val baselineGetter = baselineGetters.single()
        bridge.addInstructions(0, """
            :try_start
            move-object/from16 v0, p0
            invoke-virtual {v0}, $root
            move-result-object v0
            const/4 v1, 0x0
            const-wide/16 v2, 0x0
            const/4 v4, 0x0
            const/4 v5, 0x0
            const/4 v6, 0x0
            invoke-static/range {v0 .. v6}, $CONTROLS->bind($VIEW${STRING}JZZ$STRING)V
            const/4 v2, 0x0
            const-wide/16 v3, 0x0
            const/4 v5, 0x0
            const/4 v7, 0x0
            const/4 v8, 0x0
            move-object/from16 v1, p4
            if-eqz v1, :bind
            invoke-virtual {v1}, $getter
            move-result-object v1
            if-eqz v1, :bind
            iget-object v2, v1, ${fields[0]}
            iget-wide v3, v1, ${fields[2]}
            move-object/from16 v6, p2
            if-eqz v6, :done
            invoke-interface {v6}, $adapterType->${baselineGetter.name}()$STRING
            move-result-object v7
            iget-object v6, v1, ${fields[21]}
            if-eqz v6, :eligibility
            instance-of v5, v6, $markerType
            if-eqz v5, :eligibility
            check-cast v6, $markerType
            iget-object v6, v6, $firstUnreadField
            if-eqz v6, :eligibility
            invoke-virtual {v6}, Ljava/lang/Long;->longValue()J
            move-result-wide v5
            cmp-long v8, v3, v5
            if-nez v8, :not_first
            const/4 v8, 0x1
            goto :eligibility
            :not_first
            const/4 v8, 0x0
            :eligibility
            const/4 v5, 0x0
            iget-boolean v6, v1, ${fields[6]}
            if-eqz v6, :bind
            iget-boolean v6, v1, ${fields[10]}
            if-nez v6, :bind
            iget-boolean v6, v1, ${fields[12]}
            if-eqz v6, :bind
            iget-boolean v6, v1, ${fields[13]}
            if-nez v6, :bind
            const/4 v5, 0x1
            :bind
            move-object v1, v2
            move-wide v2, v3
            move v4, v5
            move v5, v8
            move-object v6, v7
            invoke-static/range {v0 .. v6}, $CONTROLS->bind($VIEW${STRING}JZZ$STRING)V
            :try_end
            goto :done
            :catch
            move-exception v0
            :done
            return-void
            .catch Ljava/lang/Throwable; {:try_start .. :try_end} :catch
        """.trimIndent())
        binder.classDef.methods.add(bridge)
        binder.classDef.directMethods.add(bridge)
        // 全 parameter は連続し、元 register の値を変更しない。高位 register でも range が安全。
        binder.method.addInstructions(0,
            "invoke-static/range {p0 .. p8}, ${binder.originalClassDef.type}->$BIND_CONTROLS(${parameters.joinToString("")})V")
        recordFeatureStatus(ids, 2, 2, "ReadControlsInjected")
    }
}

/** checker 自身が比較に使う read_up field の単純な公開 getter 以外は採用しません。 */
internal fun isNativeReadPositionGetter(method: Method, readPosition: FieldReference): Boolean {
    val body = method.implementation?.instructions?.toList().orEmpty()
    return method.parameterTypes.isEmpty() && method.returnType == STRING &&
        AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        body.map { it.opcode } == listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT) &&
        (body.firstOrNull() as? ReferenceInstruction)?.reference == readPosition
}

/** 表示タイミングの boolean gate ではなく、native が保持する正確な先頭未読 ID を使います。 */
internal fun nativeFirstUnreadField(checker: Method): FieldReference? =
    checker.implementation?.instructions?.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? FieldReference
    }?.filter { it.definingClass == checker.definingClass && it.type == "Ljava/lang/Long;" }
        ?.distinct()?.singleOrNull()
