package dev.utaa.linimal.patches.features.readwithoutreceipt

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadWithoutReceiptMessageControlsPatchTest {
    @Test
    fun `native baseline getter must return the exact string field compared by unread checker`() {
        val baseline = ImmutableFieldReference("Lrenamed/adapter;", "snapshot", "Ljava/lang/String;")
        val other = ImmutableFieldReference("Lrenamed/adapter;", "unrelated", "Ljava/lang/String;")
        val getter = ImmutableMethod(baseline.definingClass, "renamedGetter", emptyList(), baseline.type,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(2, listOf(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, baseline),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ), emptyList(), emptyList()))
        assertTrue(isNativeReadPositionGetter(getter, baseline))
        assertFalse(isNativeReadPositionGetter(getter, other))
        val unknown = ImmutableMethod(getter.definingClass, getter.name, getter.parameters,
            getter.returnType, getter.accessFlags, null, null,
            ImmutableMethodImplementation(2, listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                emptyList(), emptyList()))
        assertFalse(isNativeReadPositionGetter(unknown, baseline))
        val static = ImmutableMethod(getter.definingClass, getter.name, getter.parameters,
            getter.returnType, getter.accessFlags or AccessFlags.STATIC.value, null, null, getter.implementation)
        assertFalse(isNativeReadPositionGetter(static, baseline))
    }

    @Test
    fun `first unread cache is distinct from native display lifecycle gate and requires unique owner field`() {
        val owner = "Lrenamed/adapter;"
        val cached = ImmutableFieldReference(owner, "firstUnread", "Ljava/lang/Long;")
        val displayGate = ImmutableFieldReference(owner, "markerEnabled", "Z")
        val unrelated = ImmutableFieldReference("Lother/owner;", "another", "Ljava/lang/Long;")
        fun checker(fields: List<ImmutableFieldReference>) = ImmutableMethod(owner, "marker", emptyList(), "Z",
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(2,
                fields.map { ImmutableInstruction22c(
                    if (it.type == "Z") Opcode.IGET_BOOLEAN else Opcode.IGET_OBJECT, 0, 1, it)
                } + ImmutableInstruction11x(Opcode.RETURN, 0), emptyList(), emptyList()))
        assertEquals(cached, nativeFirstUnreadField(checker(listOf(displayGate, cached, unrelated))))
        assertNull(nativeFirstUnreadField(checker(listOf(displayGate, unrelated))))
        val ambiguous = ImmutableFieldReference(owner, "anotherCache", "Ljava/lang/Long;")
        assertNull(nativeFirstUnreadField(checker(listOf(displayGate, cached, ambiguous))))
    }
}
