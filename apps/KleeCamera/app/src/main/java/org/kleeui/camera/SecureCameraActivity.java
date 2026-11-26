package org.kleeui.camera;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.WindowManager;

/** A photo-only camera session isolated from the launcher activity and its gallery. */
public final class SecureCameraActivity extends MainActivity {
    private final BroadcastReceiver closeSession = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };
    private boolean closeSessionRegistered;

    public SecureCameraActivity() {
        super(true);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (isFinishing()) return;

        DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
        if (policy != null && (policy.getKeyguardDisabledFeatures(null)
                & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0) {
            finish();
            return;
        }

        IntentFilter events = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        events.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(closeSession, events);
        closeSessionRegistered = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setShowWhenLocked(true);
    }

    @Override
    protected void onDestroy() {
        if (closeSessionRegistered) unregisterReceiver(closeSession);
        super.onDestroy();
    }
}
