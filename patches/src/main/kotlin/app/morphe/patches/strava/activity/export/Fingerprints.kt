/**
 * Copyright 2026 De-Vanced
 * https://github.com/RookieEnough/De-Vanced/pull/117
 */
package app.morphe.patches.strava.activity.export

import app.morphe.patcher.Fingerprint

private const val ACTIVITY_DETAIL_PACKAGE = "Lcom/strava/activitydetail/universal/"
private const val MODULAR_FRAMEWORK_MVP_PACKAGE = "Lcom/strava/modularframework/mvp/"

/**
 * Constructor of the activity detail (ADP) modular response, which carries the entry
 * container that holds the activity's overflow menu items.
 */
internal object AdpModularResponseSuccessInitFingerprint : Fingerprint(
    definingClass = "AdpModularResponse\$Success;",
    name = "<init>",
    parameters = listOf("Lcom/strava/modularframework/data/ModularEntryContainer;"),
)

/**
 * The activity detail (ADP) view model's modular event handler, which navigates to the
 * destination of the menu item that was tapped.
 *
 * Both the view model and the event type are obfuscated, so match on the packages they
 * live in rather than on their names.
 */
internal object AdpViewModelOnEventFingerprint : Fingerprint(
    definingClass = ACTIVITY_DETAIL_PACKAGE,
    name = "onEvent",
    returnType = "V",
    parameters = listOf(MODULAR_FRAMEWORK_MVP_PACKAGE)
)
