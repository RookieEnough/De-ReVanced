/**
 * Copyright 2026 De-Vanced
 * https://github.com/RookieEnough/De-Vanced/pull/112
 */
package app.morphe.patches.strava.route.export

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.strava.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/strava/AddRouteExportPatch;"

@Suppress("unused")
val addRouteExportPatch = bytecodePatch(
    name = "Add route export",
    description = "Allows exporting and downloading Strava routes as GPX or TCX files directly from the app.",
) {
    compatibleWith(AppCompatibilities.STRAVA)

    dependsOn(
        resourceMappingPatch,
        sharedExtensionPatch
    )

    execute {
        // Hook: capture ShareObject to extract the route ID.
        ShareObjectHandlerFingerprint.matchAll().forEach { match ->
            val mutableMethod = match.method
            mutableMethod.addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->onShareObject(Ljava/lang/Object;)V
                """.trimIndent(),
            )
        }

        // Hook: trigger export dialog when ShareSheetActivity opens.
        ShareSheetActivityOnCreateFingerprint.let { fingerprint ->
            val match = fingerprint.match()
            match.method.addInstructions(
                0,
                """
                    invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onShareSheetActivityStarted(Landroid/app/Activity;)V
                """.trimIndent(),
            )
        }

        // Hook: trigger export dialog when CopyLinkToClipboardActivity opens.
        CopyLinkActivityOnCreateFingerprint.let { fingerprint ->
            val match = fingerprint.match()
            match.method.addInstructions(
                0,
                """
                    invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onCopyLinkActivityStarted(Landroid/app/Activity;)V
                """.trimIndent(),
            )
        }
    }
}
