/*
 * Copyright 2025 De-Vanced.
 * https://github.com/RookieEnough/De-Vanced
 */

package app.morphe.patches.pixiv.popularsearch

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val removePopularSearchTimeLimitPatch = bytecodePatch(
    name = "Remove popular search time limit",
    description = "Removes the 7-day trial countdown on popular search results so the free " +
        "popular-search preview (30 works) never expires.",
) {
    compatibleWith(AppCompatibilities.PIXIV)

    execute {
        PremiumTrialServiceGetPremiumTrialExpireDaysFingerprint.let {
            it.method.apply {
                val daysSinceFirstLaunchSubIndex = it.instructionMatches.first().index
                val register = getInstruction<OneRegisterInstruction>(daysSinceFirstLaunchSubIndex).registerA

                replaceInstruction(
                    daysSinceFirstLaunchSubIndex,
                    "const/4 v$register, 0x7"
                )
            }
        }
    }
}
