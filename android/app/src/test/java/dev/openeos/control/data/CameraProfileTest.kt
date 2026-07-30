package dev.openeos.control.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraProfileTest {
    @Test
    fun r6MarkThirdAliasesUseThePrimaryProfile() {
        listOf(
            "Canon EOS R6 Mark III",
            "EOS R6 Mark III",
            "R6 Mark III",
            "R6m3",
            "R63",
            "Canon EOS-R6 Mark III",
        ).forEach { model ->
            val profile = CameraProfile.fromModelName(model)
            assertEquals(model, CameraModelFamily.EOS_R, profile.family)
            assertEquals(model, CameraModelPriority.PRIMARY, profile.priority)
            assertEquals(model, profile.modelName)
        }
    }

    @Test
    fun otherFamiliesRetainTheirCompatibilityPriority() {
        assertEquals(
            CameraProfile("Canon EOS R5", CameraModelFamily.EOS_R, CameraModelPriority.SUPPORTED),
            CameraProfile.fromModelName("Canon EOS R5"),
        )
        assertEquals(
            CameraProfile("Canon EOS M50", CameraModelFamily.EOS_M, CameraModelPriority.SUPPORTED),
            CameraProfile.fromModelName("Canon EOS M50"),
        )
        assertEquals(
            CameraProfile("Canon EOS 5D", CameraModelFamily.EOS_DSLR, CameraModelPriority.SUPPORTED),
            CameraProfile.fromModelName("Canon EOS 5D"),
        )
        assertEquals(
            CameraProfile("Canon PowerShot G7 X", CameraModelFamily.POWERSHOT, CameraModelPriority.RESEARCH),
            CameraProfile.fromModelName("Canon PowerShot G7 X"),
        )
    }
}
