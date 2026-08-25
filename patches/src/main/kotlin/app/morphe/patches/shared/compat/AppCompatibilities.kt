/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/shared/compat/AppCompatibilities.kt
 *
 * Central Morphe `Compatibility` metadata so Morphe Manager shows human-readable app names.
 */
package app.morphe.patches.shared.compat

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

@Suppress("MemberVisibilityCanBePrivate")
internal object AppCompatibilities {
    val AMAZON_MUSIC = Compatibility(
        name = "Amazon Music",
        packageName = "com.amazon.mp3",
        appIconColor = 0xFF9900,
    )

    val AMAZON_SHOPPING = Compatibility(
        name = "Amazon Shopping",
        packageName = "com.amazon.mShop.android.shopping",
        appIconColor = 0xFF9900,
    )

    val ANGULUS = Compatibility(
        name = "Angulus",
        packageName = "com.drinkplusplus.angulus",
        appIconColor = 0x2196F3,
    )

    val BANDCAMP = Compatibility(
        name = "Bandcamp",
        packageName = "com.bandcamp.android",
        appIconColor = 0x629AA9,
    )

    val GMX_MAIL = Compatibility(
        name = "GMX Mail",
        packageName = "de.gmx.mobile.android.mail",
        appIconColor = 0x003399,
    )

    val GOOGLE_RECORDER = Compatibility(
        name = "Google Recorder",
        packageName = "com.google.android.apps.recorder",
        appIconColor = 0x4285F4,
    )

    val HEX_EDITOR = Compatibility(
        name = "Hex Editor",
        packageName = "com.myprog.hexedit",
        appIconColor = 0x607D8B,
    )

    val IRPLUS = Compatibility(
        name = "irplus",
        packageName = "net.binarymode.android.irplus",
        appIconColor = 0xFF5722,
    )

    val NOTHING_X = Compatibility(
        name = "Nothing X",
        packageName = "com.nothing.smartcenter",
        appIconColor = 0x000000,
    )

    val NU_NL = Compatibility(
        name = "NU.nl",
        packageName = "nl.sanomamedia.android.nu",
        appIconColor = 0xE2001A,
    )

    val PHOTOSHOP_MIX = Compatibility(
        name = "Photoshop Mix",
        packageName = "com.adobe.photoshopmix",
        appIconColor = 0x31A8FF,
    )

    val FACEBOOK = Compatibility(
        name = "Facebook",
        packageName = "com.facebook.katana",
        appIconColor = 0x0866FF,
        targets = listOf(
            AppTarget(
                version = "490.0.0.63.82",
                versionCodes = mapOf(
                    SupportedAbi.ARMEABI_V7A to 457019986,
                    SupportedAbi.ARM64_V8A to 457019914
                )
            )
        )
    )

    val MESSENGER = Compatibility(
        name = "Messenger",
        packageName = "com.facebook.orca",
        appIconColor = 0x0866FF,
        targets = listOf(
            AppTarget(
                version = "573.0.0.44.88",
                versionCodes = mapOf(
                    SupportedAbi.ARMEABI_V7A to 344611781,
                    SupportedAbi.ARM64_V8A to 344611864
                )
            )
        )
    )

    val GOOGLE_PHOTOS = Compatibility(
        name = "Google Photos",
        packageName = "com.google.android.apps.photos",
        appIconColor = 0xFC3F3C,
    )

    val LETTERBOXD = Compatibility(
        name = "Letterboxd",
        packageName = "com.letterboxd.letterboxd",
        appIconColor = 0x00B020,
    )

    val PIXIV = Compatibility(
        name = "Pixiv",
        packageName = "jp.pxv.android",
        appIconColor = 0x0096FA,
        targets = listOf(AppTarget("6.141.1")),
    )

    val CRICBUZZ = Compatibility(
        name = "Cricbuzz",
        packageName = "com.cricbuzz.android",
        appIconColor = 0x009270,
        targets = listOf(AppTarget("6.24.01")),
    )

    val MICROSOFT_LENS = Compatibility(
        name = "Microsoft Lens",
        packageName = "com.microsoft.office.officelens",
        appIconColor = 0xD73E01,
    )

    val PHOTOMATH = Compatibility(
        name = "Photomath",
        packageName = "com.microblink.photomath",
        appIconColor = 0xD23F3F,
    )

    val ICON_PACK_STUDIO = Compatibility(
        name = "Icon Pack Studio",
        packageName = "ginlemon.iconpackstudio",
        appIconColor = 0xF4287D,
        targets = listOf(AppTarget("2.2 build 016")),
    )

    val STRAVA = Compatibility(
        name = "Strava",
        packageName = "com.strava",
        appIconColor = 0xFC6925,
        targets = listOf(AppTarget("477.14"))
    )

    val TWITCH = Compatibility(
        name = "Twitch",
        packageName = "tv.twitch.android.app",
        appIconColor = 0x9146FF,
        targets = listOf(AppTarget("16.9.1"), AppTarget("25.3.0")),
    )

    val VIBER = Compatibility(
        name = "Viber",
        packageName = "com.viber.voip",
        appIconColor = 0x7360F2,
    )

    val VIBER_VERSIONED = Compatibility(
        name = "Viber",
        packageName = "com.viber.voip",
        appIconColor = 0x7360F2,
        targets = listOf(AppTarget("25.9.2.0"), AppTarget("26.1.2.0")),
    )
}
