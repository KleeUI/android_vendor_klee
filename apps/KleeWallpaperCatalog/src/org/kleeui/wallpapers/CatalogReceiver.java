// Copyright (C) 2026 The KleeUI Project
// SPDX-License-Identifier: Apache-2.0

package org.kleeui.wallpapers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Identifies the built-in wallpaper resources to Android's wallpaper picker. */
public final class CatalogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // The picker queries this component to locate the resources.
    }
}
