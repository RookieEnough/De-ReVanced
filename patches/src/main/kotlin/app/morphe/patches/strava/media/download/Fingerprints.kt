/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/strava/media/download/Fingerprints.kt
 */
package app.morphe.patches.strava.media.download

import app.morphe.patcher.Fingerprint

internal object HandleMediaActionFingerprint : Fingerprint(
    parameters = listOf(
        "Landroid/view/View;",
        "Lcom/strava/bottomsheet/BottomSheetItem;",
    ),
)
