package com.emergencyguardian;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

/**
 * NetworkManager — checks internet connectivity and logs the SOS upload status.
 *
 * ── Responsibilities ──────────────────────────────────────────────────────────
 *   1. Determine whether the device has a validated internet connection.
 *   2. Log the result in the standard SOS workflow format.
 *
 * ── What this class does NOT do ───────────────────────────────────────────────
 *   • Does NOT upload anything. No HTTP calls are made.
 *   • Does NOT replace RelayManager.isInternetAvailable(), which runs at the
 *     relay level on received packets. This class runs at the origin level
 *     when the SOS is first triggered on this device.
 *
 * ── Future extensibility ──────────────────────────────────────────────────────
 *   When internet upload is implemented, add an UploadChannel interface here
 *   (mirroring EmergencyMessenger.DeliveryChannel) and call it from
 *   checkAndLogStatus() after the internet check passes.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 *   All methods are stateless and thread-safe. checkAndLogStatus() is typically
 *   called from the main thread inside onSOSTriggered(), but is safe to call
 *   from any thread.
 */
public class NetworkManager {

    private static final String TAG = "NetworkManager";

    private final Context context;

    // ─── Constructor ──────────────────────────────────────────────────────────

    /**
     * @param context Any context; application context is used internally.
     */
    public NetworkManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns true if the device currently has a validated internet connection.
     *
     * Uses ConnectivityManager + NetworkCapabilities with NET_CAPABILITY_VALIDATED,
     * which means the network has actually passed an internet reachability probe —
     * not just "connected to Wi-Fi" which may be a captive portal.
     *
     * Requires: ACCESS_NETWORK_STATE (declared in AndroidManifest.xml).
     */
    public boolean isInternetAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        android.net.Network active = cm.getActiveNetwork();
        if (active == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null) return false;

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Checks internet connectivity and logs the result in the SOS workflow format.
     *
     * Call this immediately after an SOS packet is created (inside onSOSTriggered),
     * before the packet is dispatched to the mesh.
     *
     * When internet IS available:
     *   → Log.i "Internet Available"
     *   → Log.i "SOS Ready To Upload"
     *
     * When internet IS NOT available:
     *   → Log.i "No Internet Available"
     *   → Log.i "Continue Mesh Relay"
     *
     * @return true if internet is available.
     *         The caller can use this return value to branch upload logic later.
     */
    public boolean checkAndLogStatus() {
        if (isInternetAvailable()) {
            Log.i(TAG, "Internet Available");
            Log.i(TAG, "SOS Ready To Upload");
            return true;
        } else {
            Log.i(TAG, "No Internet Available");
            Log.i(TAG, "Continue Mesh Relay");
            return false;
        }
    }
}
