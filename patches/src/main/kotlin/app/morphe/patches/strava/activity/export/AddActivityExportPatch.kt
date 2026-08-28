/**
 * Copyright 2026 De-Vanced
 * https://github.com/RookieEnough/De-Vanced/pull/117
 */
package app.morphe.patches.strava.activity.export

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.strava.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/strava/AddActivityExportPatch;"

@Suppress("unused")
val addActivityExportPatch = bytecodePatch(
    name = "Add activity export",
    description = "Adds an \"Export GPX\" item to the activity overflow menu, which saves the " +
        "activity's GPS track as a GPX file in Downloads/Strava.",
) {
    compatibleWith(AppCompatibilities.STRAVA)

    dependsOn(sharedExtensionPatch)

    execute {
        // Append the "Export GPX" item to the activity's modular menu.
        AdpModularResponseSuccessInitFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->augmentAdpMenu(Lcom/strava/modularframework/data/ModularEntryContainer;)V
            """.trimIndent(),
        )

        // Consume the navigation event of the item, which carries the extension's sentinel destination.
        AdpViewModelOnEventFingerprint.method.apply {
            // `onEvent` is large, so the event parameter is not addressable by `invoke-static`.
            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->onAdpEvent(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :morphe_continue
                    return-void
                """.trimIndent(),
                ExternalLabel("morphe_continue", instructions.first()),
            )
        }
    }
}
