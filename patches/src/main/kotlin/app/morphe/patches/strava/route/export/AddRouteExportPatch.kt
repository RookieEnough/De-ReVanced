package app.morphe.patches.strava.route.export

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.strava.misc.extension.sharedExtensionPatch
import app.morphe.util.findMutableMethodOf

private const val ROUTE_EXPORT_CLASS_DESCRIPTOR = "Lapp/morphe/extension/strava/AddRouteExportPatch;"

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
        classDefForEach { classDef ->
            // Hook 1: AC/E (capture ShareObject safely at method entry index 0)
            if (classDef.type == "LAC/E;") {
                val mutableClass = mutableClassDefBy(classDef)
                classDef.methods.forEach { method ->
                    if (method.name == "a" && method.parameterTypes.any { it.contains("ShareObject") }) {
                        val mutableMethod = mutableClass.findMutableMethodOf(method)
                        mutableMethod.addInstructions(
                            0,
                            """
                                invoke-static { p1 }, $ROUTE_EXPORT_CLASS_DESCRIPTOR->onShareObject(Ljava/lang/Object;)V
                            """.trimIndent(),
                        )
                    }
                }
            }

            // Hook 1b: AC/C2274d (capture ShareObject from static Shareable factory)
            if (classDef.type == "LAC/C2274d;") {
                val mutableClass = mutableClassDefBy(classDef)
                classDef.methods.forEach { method ->
                    if (method.name == "a" && method.parameterTypes.any { it.contains("ShareObject") }) {
                        val mutableMethod = mutableClass.findMutableMethodOf(method)
                        mutableMethod.addInstructions(
                            0,
                            """
                                invoke-static { p0 }, $ROUTE_EXPORT_CLASS_DESCRIPTOR->onShareObject(Ljava/lang/Object;)V
                            """.trimIndent(),
                        )
                    }
                }
            }

            // Hook 3: ShareSheetActivity (trigger export dialog when the ShareSheet Activity opens)
            if (classDef.type == "Lcom/strava/sharing/view/ShareSheetActivity;") {
                val mutableClass = mutableClassDefBy(classDef)
                classDef.methods.forEach { method ->
                    if (method.name == "onCreate") {
                        val mutableMethod = mutableClass.findMutableMethodOf(method)
                        mutableMethod.addInstructions(
                            0,
                            """
                                invoke-static { p0 }, $ROUTE_EXPORT_CLASS_DESCRIPTOR->onShareSheetActivityStarted(Landroid/app/Activity;)V
                            """.trimIndent(),
                        )
                    }
                }
            }

            // Hook 4: CopyLinkToClipboardActivity (fallback instant export dialog)
            if (classDef.type == "Lcom/strava/sharinginterface/CopyLinkToClipboardActivity;") {
                val mutableClass = mutableClassDefBy(classDef)
                classDef.methods.forEach { method ->
                    if (method.name == "onCreate") {
                        val mutableMethod = mutableClass.findMutableMethodOf(method)
                        mutableMethod.addInstructions(
                            0,
                            """
                                invoke-static { p0 }, $ROUTE_EXPORT_CLASS_DESCRIPTOR->onCopyLinkActivityStarted(Landroid/app/Activity;)V
                            """.trimIndent(),
                        )
                    }
                }
            }
        }
    }
}

