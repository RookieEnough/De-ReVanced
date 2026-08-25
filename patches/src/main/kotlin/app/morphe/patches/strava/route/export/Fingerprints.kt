/**
 * Copyright 2026 De-Vanced
 * https://github.com/RookieEnough/De-Vanced/pull/112
 */
package app.morphe.patches.strava.route.export

import app.morphe.patcher.Fingerprint

internal object ShareSheetActivityOnCreateFingerprint : Fingerprint(
    definingClass = "/ShareSheetActivity;",
    name = "onCreate",
    parameters = listOf("Landroid/os/Bundle;"),
)

internal object CopyLinkActivityOnCreateFingerprint : Fingerprint(
    definingClass = "/CopyLinkToClipboardActivity;",
    name = "onCreate",
    parameters = listOf("Landroid/os/Bundle;"),
)
