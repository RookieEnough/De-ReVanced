/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 */

package app.morphe.patches.pixiv.popularsearch

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val removePopularSearchTimeLimitPatch = bytecodePatch(
    name = "Remove popular search time limit",
    description = "Removes the 7-day trial countdown on popular search results so the free " +
        "popular-search preview (30 works) never expires.",
) {
    compatibleWith(AppCompatibilities.PIXIV)

    execute {
        PremiumTrialServiceGetPremiumTrialExpireDaysFingerprint.method.run {
            val daysSinceFirstLaunchSubIndex = indexOfFirstInstructionOrThrow(Opcode.RSUB_INT_LIT8)

            val instruction = getInstruction<OneRegisterInstruction>(daysSinceFirstLaunchSubIndex)

            replaceInstruction(
                daysSinceFirstLaunchSubIndex,
                "const/4 v${instruction.registerA}, 0x7",
            )
        }
    }
}
