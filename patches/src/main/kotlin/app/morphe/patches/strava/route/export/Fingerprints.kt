package app.morphe.patches.strava.route.export

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object RouteDetailShareFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    strings = listOf("route"),
)

internal object HandleRouteActionFingerprint : Fingerprint(
    parameters = listOf(
        "Landroid/view/View;",
        "Lcom/strava/bottomsheet/BottomSheetItem;",
    ),
)
