/*
 * Copyright 2025 De-Vanced.
 * https://github.com/RookieEnough/De-Vanced
 */

package app.morphe.patches.pixiv.popularsearch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object PremiumTrialServiceGetPremiumTrialExpireDaysFingerprint : Fingerprint(
    definingClass = "/PremiumTrialService;",
    name = "getPremiumTrialExpireDays",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
    filters = listOf(
        opcode(Opcode.RSUB_INT_LIT8)
    )
)
