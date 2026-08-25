/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/strava/groupkudos/AddGiveGroupKudosButtonToGroupActivity.kt
 */
package app.morphe.patches.strava.groupkudos

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.strava.misc.extension.sharedExtensionPatch
import app.morphe.util.childElementsSequence
import app.morphe.util.containsLiteralInstruction
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.w3c.dom.Element

private const val GIVE_KUDOS_ON_CLICK_LISTENER_DESCRIPTOR =
    "Lapp/morphe/extension/strava/GiveKudosOnClickListener;"
private const val ATTACH_LISTENER_METHOD_DESCRIPTOR = "$GIVE_KUDOS_ON_CLICK_LISTENER_DESCRIPTOR->" +
    "attach(Landroid/view/View;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V"

private var shakeToKudosStringId = -1
private var kudosIdId = -1
private var leaveIdId = -1

private val addGiveKudosButtonToLayoutPatch = resourcePatch {
    fun String.toResourceId() = substring(2).toInt(16)

    execute {
        document("res/values/public.xml").use { public ->
            fun Sequence<Element>.firstByName(name: String) = first {
                it.getAttribute("name") == name
            }

            val publicElements = public.documentElement.childElementsSequence().filter {
                it.tagName == "public"
            }
            val idElements = publicElements.filter {
                it.getAttribute("type") == "id"
            }
            val stringElements = publicElements.filter {
                it.getAttribute("type") == "string"
            }

            shakeToKudosStringId =
                stringElements.firstByName("shake_to_kudos_dialog_title").getAttribute("id").toResourceId()

            val kudosIdNode = idElements.firstByName("kudos").apply {
                kudosIdId = getAttribute("id").toResourceId()
            }

            document("res/layout/grouped_activities_dialog_group_tab.xml").use { layout ->
                layout.childNodes.findElementByAttributeValueOrThrow(
                    "android:id",
                    "@id/leave_group_button_container"
                ).apply {
                    // Change from "FrameLayout".
                    layout.renameNode(this, namespaceURI, "LinearLayout")

                    val leaveButton = childElementsSequence().first()
                    // Get "Leave Group" button ID for bytecode matching.
                    val leaveButtonIdName = leaveButton.getAttribute("android:id").substringAfter('/')
                    leaveIdId = idElements.firstByName(leaveButtonIdName).getAttribute("id").toResourceId()

                    // Add surrounding padding to offset decrease on buttons.
                    setAttribute("android:paddingHorizontal", "@dimen/space_2xs")

                    // Place buttons next to each other with equal width.
                    val kudosButton = leaveButton.apply {
                        setAttribute("android:layout_width", "0dp")
                        setAttribute("android:layout_weight", "1")
                        // Decrease padding between buttons from "@dimen/button_large_padding" ...
                        setAttribute("android:paddingHorizontal", "@dimen/space_xs")
                    }.cloneNode(true) as Element
                    kudosButton.apply {
                        setAttribute("android:id", "@id/${kudosIdNode.getAttribute("name")}")
                        setAttribute("android:text", "@string/kudos_button")
                    }.let(::appendChild)

                    // Downgrade emphasis of "Leave Group" button from "primary".
                    leaveButton.setAttribute("app:emphasis", "secondary")
                }
            }
        }
    }
}

@Suppress("unused")
val addGiveGroupKudosButtonToGroupActivity = bytecodePatch(
    name = "Add 'Give Kudos' button to 'Group Activity'",
    description = "Adds a button that triggers the same action as shaking your phone would.",
) {
    compatibleWith(AppCompatibilities.STRAVA)

    dependsOn(addGiveKudosButtonToLayoutPatch, sharedExtensionPatch)

    execute {
        val viewDelegateClassDef = InitFingerprint.originalClassDef

        // Method that renders the view states, one of which shows the "Give Kudos" dialog.
        // Cannot be matched on the Kotlin parameter null check string "state",
        // because the app no longer contains those strings.
        val actionHandlerMethod = viewDelegateClassDef.methods.firstOrNull { method ->
            method.containsLiteralInstruction(shakeToKudosStringId.toLong())
        } ?: throw PatchException(
            "Could not find the method that shows the 'Give Kudos' dialog in: ${viewDelegateClassDef.type}"
        )

        // Singleton instance of the state that makes the action handler show the "Give Kudos" dialog.
        val giveKudosStateReference = actionHandlerMethod.implementation!!.instructions
            .take(actionHandlerMethod.indexOfFirstLiteralInstructionOrThrow(shakeToKudosStringId.toLong()))
            .last { instruction -> instruction.opcode == Opcode.SGET_OBJECT }
            .getReference<FieldReference>()!!

        InitFingerprint.method.apply {
            // Instructions that inflate the "Leave Group" button, which the "Give Kudos" button is cloned from.
            val constLeaveIdIndex = indexOfFirstLiteralInstructionOrThrow(leaveIdId.toLong())
            val findViewByIdInstruction = getInstruction<BuilderInstruction35c>(constLeaveIdIndex + 1)
            val fragmentRegister = findViewByIdInstruction.registerC

            // The app packs this constructor tightly, so free registers must be found
            // instead of assuming any are available.
            val freeRegisters = getFreeRegisterProvider(constLeaveIdIndex, 3, fragmentRegister)
            val viewRegister = freeRegisters.getFreeRegister4Bit()
            val stateRegister = freeRegisters.getFreeRegister4Bit()
            val methodNameRegister = freeRegisters.getFreeRegister4Bit()

            addInstructions(
                constLeaveIdIndex,
                """
                    const v$viewRegister, $kudosIdId
                    ${findViewByIdInstruction.opcode.name} { v$fragmentRegister, v$viewRegister }, ${findViewByIdInstruction.reference}
                    move-result-object v$viewRegister
                    sget-object v$stateRegister, $giveKudosStateReference
                    const-string v$methodNameRegister, "${actionHandlerMethod.name}"
                    invoke-static { v$viewRegister, p0, v$stateRegister, v$methodNameRegister }, $ATTACH_LISTENER_METHOD_DESCRIPTOR
                """.trimIndent(),
            )
        }
    }
}
