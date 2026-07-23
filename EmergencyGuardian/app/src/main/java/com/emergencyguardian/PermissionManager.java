package com.emergencyguardian;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * PermissionManager — centralises all runtime permission requests and checks.
 *
 * Permissions managed:
 *   • ACCESS_FINE_LOCATION    — BLE scanning on API 29-30; location status checks.
 *   • BLUETOOTH_SCAN          — required on API 31+ (Android 12+).
 *   • BLUETOOTH_CONNECT       — required on API 31+ (Android 12+).
 *   • BLUETOOTH_ADVERTISE     — required on API 31+ for BLE advertising (mesh layer).
 *
 * Usage:
 *   1. Call checkAndRequestAll() from onCreate() — requests any missing permissions.
 *   2. Forward onRequestPermissionsResult() from the Activity to handleResult().
 *   3. Query individual permission state with hasLocationPermission() etc.
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";

    // Request codes
    public static final int REQUEST_CODE_ALL_PERMISSIONS = 100;

    // Callback interface
    public interface PermissionCallback {
        /**
         * Called after the user responds to the permission dialog.
         *
         * @param allGranted true if every requested permission was granted.
         */
        void onPermissionResult(boolean allGranted);
    }

    private final Activity activity;
    private PermissionCallback callback;

    public PermissionManager(Activity activity) {
        this.activity = activity;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Checks which permissions are missing and requests them in a single dialog.
     * If all permissions are already granted, {@code callback} is called immediately
     * with {@code allGranted = true}.
     *
     * @param callback Receives the result after the user responds.
     */
    public void checkAndRequestAll(@NonNull PermissionCallback callback) {
        this.callback = callback;

        List<String> missing = getMissingPermissions();
        if (missing.isEmpty()) {
            Log.d(TAG, "All permissions already granted.");
            callback.onPermissionResult(true);
            return;
        }

        Log.d(TAG, "Requesting permissions: " + missing);
        ActivityCompat.requestPermissions(
                activity,
                missing.toArray(new String[0]),
                REQUEST_CODE_ALL_PERMISSIONS
        );
    }

    /**
     * Forward the Activity's onRequestPermissionsResult() here.
     * Returns true if this manager handled the request code.
     */
    public boolean handleResult(int requestCode,
                                @NonNull String[] permissions,
                                @NonNull int[] grantResults) {
        if (requestCode != REQUEST_CODE_ALL_PERMISSIONS) return false;

        boolean allGranted = true;
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission DENIED: " + permissions[i]);
                allGranted = false;
            } else {
                Log.d(TAG, "Permission GRANTED: " + permissions[i]);
            }
        }

        if (callback != null) {
            callback.onPermissionResult(allGranted);
        }
        return true;
    }

    // ─── Individual permission checks ────────────────────────────────────────

    /** Returns true if ACCESS_FINE_LOCATION is granted. */
    public boolean hasLocationPermission() {
        return isGranted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /**
     * Returns true if all Bluetooth-related permissions are granted.
     * On API 31+ includes SCAN, CONNECT, and ADVERTISE.
     * On API < 31 only ACCESS_FINE_LOCATION is needed for BLE scanning.
     */
    public boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return isGranted(Manifest.permission.BLUETOOTH_SCAN)
                    && isGranted(Manifest.permission.BLUETOOTH_CONNECT)
                    && isGranted(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        return hasLocationPermission();
    }

    /**
     * Returns true if BLUETOOTH_ADVERTISE is granted (API 31+) or
     * the device is on API < 31 where no explicit advertise permission exists.
     */
    public boolean hasAdvertisePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return isGranted(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        return true; // Not needed on API < 31.
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /** Builds a list of permissions that are needed but not yet granted. */
    private List<String> getMissingPermissions() {
        List<String> missing = new ArrayList<>();

        // Location — needed on all supported API levels for BLE scanning and
        // for the LocationManager status display.
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Android 12+ requires explicit Bluetooth runtime permissions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isGranted(Manifest.permission.BLUETOOTH_SCAN)) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!isGranted(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!isGranted(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        }

        return missing;
    }

    private boolean isGranted(String permission) {
        return ContextCompat.checkSelfPermission(activity, permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
