package dev.utaa.linimal.patches

import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import dev.utaa.linimal.patches.core.noOpProbePatch
import dev.utaa.linimal.patches.status.patchStatusResourcePatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinimalPatchTest {
    @Test
    fun `Linimal is available only for arm64 targets`() {
        val availability = assertNotNull(linimalPatch.availability)

        InstallerType.entries.forEach { installer ->
            assertEquals(
                PatchAvailability.ENABLED,
                availability.resolve(installer, ApkArchitecture.ARM64_V8A),
                "Expected arm64-v8a to be enabled for $installer",
            )

            ApkArchitecture.entries
                .filter { it != ApkArchitecture.ARM64_V8A }
                .forEach { architecture ->
                    assertEquals(
                        PatchAvailability.UNAVAILABLE,
                        availability.resolve(installer, architecture),
                        "Expected $architecture to be unavailable for $installer",
                    )
                }
        }
    }

    @Test
    fun `status reset precedes the probe through an explicit dependency chain`() {
        assertEquals(setOf(noOpProbePatch), linimalPatch.dependencies)
        assertTrue(noOpProbePatch.dependencies.contains(patchStatusResourcePatch))
    }
}
