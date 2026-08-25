/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/strava/groupkudos/Fingerprints.kt
 */
package app.morphe.patches.strava.groupkudos

import app.morphe.patcher.Fingerprint

internal object InitFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Lcom/strava/feed/view/modal/GroupTabFragment;",
        "Z",
        "Landroidx/fragment/app/FragmentManager;",
    ),
)
