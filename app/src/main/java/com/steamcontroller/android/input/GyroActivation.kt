package com.steamcontroller.android.input

import com.steamcontroller.android.parser.Buttons

/**
 * When gyro aiming is live.
 *
 * Always-on gyro is unusable in practice — every hand movement, including setting the
 * controller down, becomes camera movement. Steam's own default behaviour is to gate it, so
 * the default here is the right trackpad's capacitive touch flag: rest a thumb on the pad to
 * aim, lift off to stop. That costs no button and matches how the hardware is normally held.
 */
enum class GyroActivation(val id: Int, val displayName: String, val mask: Int) {
    RIGHT_PAD_TOUCH(0, "Right pad touch", Buttons.TP_RT),
    HOLD_R4        (1, "Hold R4",         Buttons.R4),
    HOLD_L4        (2, "Hold L4",         Buttons.L4),
    HOLD_LT        (3, "Hold L2",         Buttons.LT_FULL),
    ALWAYS         (4, "Always on",       0);

    companion object {
        val ALL: Array<GyroActivation> = values()
        fun fromId(id: Int): GyroActivation = ALL.firstOrNull { it.id == id } ?: RIGHT_PAD_TOUCH
    }
}
