/**
 * Copyright 2026 De-Vanced
 * https://github.com/RookieEnough/De-Vanced/pull/112
 */
package app.morphe.patches.strava.route.export

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.strava.misc.extension.sharedExtensionPatch
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags

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
        // Match any concrete method that accepts a ShareObject parameter.
        classDefForEach { classDef ->
            if (AccessFlags.INTERFACE.isSet(classDef.accessFlags)) return@classDefForEach

            val mutableClass by lazy { mutableClassDefBy(classDef) }

            classDef.methods.forEach { method ->
                if (method.implementation == null) return@forEach
                if (AccessFlags.ABSTRACT.isSet(method.accessFlags)) return@forEach

                val paramTypes = method.parameterTypes.toList()
                val shareIdx = paramTypes.indexOfFirst { it.endsWith("/ShareObject;") }
                if (shareIdx < 0) return@forEach

                val mutableMethod = mutableClass.findMutableMethodOf(method)
                val reg = if (AccessFlags.STATIC.isSet(method.accessFlags)) "p$shareIdx" else "p${shareIdx + 1}"

                mutableMethod.addInstruction(
                    0,
                    "invoke-static/range { $reg .. $reg }, $EXTENSION_CLASS_DESCRIPTOR->" +
                            "onShareObject(Ljava/lang/Object;)V"
                )
            }
        }

        // Hook: trigger export dialog when ShareSheetActivity opens.
        ShareSheetActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->" +
                    "onShareSheetActivityStarted(Landroid/app/Activity;)V"
        )

        // Hook: trigger export dialog when CopyLinkToClipboardActivity opens.
        CopyLinkActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->" +
                    "onCopyLinkActivityStarted(Landroid/app/Activity;)V"
        )
    }
}
