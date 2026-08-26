/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/strava/groupkudos/Fingerprints.kt
 */
package app.morphe.patches.strava.groupkudos

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral

internal object InitFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Lcom/strava/feed/view/modal/GroupTabFragment;",
        "Z",
        "Landroidx/fragment/app/FragmentManager;",
    )
)

/**
 * Method that renders the view states, one of which shows the "Give Kudos" dialog.
 *
 * Cannot be matched on the Kotlin parameter null check string "state",
 * because the app no longer contains those strings.
 */
internal object ActionHandlerFingerprint : Fingerprint(
    classFingerprint = InitFingerprint,
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "shake_to_kudos_dialog_title")
    )
)
