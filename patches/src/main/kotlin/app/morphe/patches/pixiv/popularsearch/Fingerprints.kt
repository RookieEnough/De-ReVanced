/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 */

package app.morphe.patches.pixiv.popularsearch

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object PremiumTrialServiceGetPremiumTrialExpireDaysFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    custom = { methodDef, classDef ->
        classDef.type.endsWith("PremiumTrialService;") && methodDef.name == "getPremiumTrialExpireDays"
    },
)
