/*
 * Copyright (C) 2026 The KleeUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.telephony;

/**
 * Compatibility API used by Qualcomm telephony components derived from MIUI.
 *
 * @hide
 */
public final class TelephonyBaseUtilsStub {
    private TelephonyBaseUtilsStub() {}

    /** Returns whether the current framework is MIUI. */
    public static boolean isMiuiRom() {
        return false;
    }
}
