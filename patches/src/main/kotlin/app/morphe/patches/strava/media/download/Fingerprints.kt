/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/strava/media/download/Fingerprints.kt
 */
package app.morphe.patches.strava.media.download

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.newInstance

/**
 * Method that builds the menu of the full-screen media viewer.
 *
 * Cannot be matched on the Kotlin parameter null check string "mediaType",
 * because the app no longer contains those strings. It is the only method
 * of the fragment that creates `Action` menu items.
 */
internal object CreateAndShowFragmentFingerprint : Fingerprint(
    definingClass = "/FullscreenMediaFragment;",
    filters = listOf(
        newInstance(ACTION_CLASS_DESCRIPTOR),
    ),
)

internal object HandleMediaActionFingerprint : Fingerprint(
    parameters = listOf(
        "Landroid/view/View;",
        "Lcom/strava/bottomsheet/BottomSheetItem;",
    ),
)
