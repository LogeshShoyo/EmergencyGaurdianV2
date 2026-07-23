package com.emergencyguardian;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import com.emergencyguardian.mesh.SOSPacket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ServerManager — sends SOS packets to the remote emergency server via HTTP POST.
 *
 * ── Responsibilities ──────────────────────────────────────────────────────────
 *   1. checkInternet()  — verify validated internet connectivity.
 *   2. uploadSOS()      — build JSON payload and POST it to SERVER_URL on a
 *                         background thread; log success or failure.
 *
 * ── What this class does NOT do ───────────────────────────────────────────────
 *   • Contains no BLE, Mesh, SOS, or UI logic.
 *   • Does not handle authentication, retries, or queueing (future sprints).
 *   • Does not run on the main thread (all network I/O is off-thread).
 *
 * ── Configuration ─────────────────────────────────────────────────────────────
 *   Update SERVER_URL to the actual endpoint before hardware testing.
 *
 * ── Upload trigger points ─────────────────────────────────────────────────────
 *   • Origin device  — MainActivity.onSOSTriggered() calls uploadSOS(sent) after
 *                      the packet is created and pushed to the mesh.
 *   • Relay device   — MainActivity.onUploadReady(packet) calls uploadSOS(packet)
 *                      when RelayManager detects internet on a received packet.
 *
 * ── JSON payload format ───────────────────────────────────────────────────────
 * {
 *   "emergencyId":       "uuid-v4",
 *   "timestamp":         1753001234000,
 *   "lastKnownLocation": { "latitude": 11.1271, "longitude": 78.6569 }  // or null
 *   "relayCount":        3,
 *   "relayHistory":      ["PhoneA", "PhoneB", "PhoneC"],
 *   "sensorTriggered":   true
 * }
 *
 * ── Requires ──────────────────────────────────────────────────────────────────
 *   AndroidManifest.xml: INTERNET + ACCESS_NETWORK_STATE permissions.
 */
public class ServerManager {

    private static final String TAG = "ServerManager";

    // ─── Server endpoint ──────────────────────────────────────────────────────

    /**
     * Remote endpoint that receives SOS POST requests.
     *
     * !! Replace with the actual server URL before hardware testing !!
     *
     * Expected behaviour from the server (not this app's responsibility):
     *   • Accept HTTP POST with Content-Type: application/json.
     *   • Return 2xx on success.
     *   • Return 4xx/5xx or time out on failure.
     */
    public static final String SERVER_URL = "https://YOUR-WEBSITE-LINK-HERE";

    /** HTTP connection timeout (ms). */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** HTTP read timeout (ms). */
    private static final int READ_TIMEOUT_MS = 15_000;

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final Context         context;

    /**
     * Single background thread for HTTP uploads.
     * Keeps uploads sequential so the main thread is never blocked and
     * simultaneous SOS events don't race on the socket.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ─── Constructor ──────────────────────────────────────────────────────────

    /**
     * @param context Any context; application context is used internally.
     */
    public ServerManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns true if the device currently has a validated internet connection.
     *
     * Uses ConnectivityManager + NET_CAPABILITY_VALIDATED, which confirms the
     * network has passed an internet reachability probe (not just "connected to
     * Wi-Fi" which could be a captive portal).
     *
     * Requires: ACCESS_NETWORK_STATE (declared in AndroidManifest.xml).
     */
    public boolean checkInternet() {
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
     * Sends an SOS packet to the server via HTTP POST on a background thread.
     *
     * Internally calls {@link #checkInternet()} before attempting the request.
     * If internet is not available, logs the reason and returns without uploading.
     *
     * On success:  logs "SOS uploaded successfully."
     * On failure:  logs "Upload failed. Continue mesh relay."
     *              The SOS packet is NOT discarded — it is still in the mesh.
     *
     * Safe to call from any thread. Network I/O runs on a dedicated background
     * executor and never blocks the calling thread.
     *
     * @param packet The SOSPacket to upload. Must not be null.
     */
    public void uploadSOS(SOSPacket packet) {
        if (packet == null) {
            Log.w(TAG, "uploadSOS() — null packet, skipping.");
            return;
        }

        executor.execute(() -> {
            if (!checkInternet()) {
                Log.w(TAG, "uploadSOS() — no internet. Upload skipped.");
                return;
            }

            try {
                String jsonBody = buildJsonPayload(packet).toString();
                byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);

                URL url = new URL(SERVER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
                conn.setDoOutput(true);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                conn.disconnect();

                if (responseCode >= 200 && responseCode < 300) {
                    Log.i(TAG, "SOS uploaded successfully."
                            + " id=" + packet.getEmergencyId()
                            + " HTTP " + responseCode);
                } else {
                    Log.e(TAG, "Upload failed. Continue mesh relay."
                            + " HTTP " + responseCode
                            + " id=" + packet.getEmergencyId());
                }

            } catch (Exception e) {
                Log.e(TAG, "Upload failed. Continue mesh relay."
                        + " id=" + packet.getEmergencyId()
                        + " error=" + e.getMessage());
            }
        });
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Builds the JSON payload from a SOSPacket.
     *
     * Format:
     * <pre>
     * {
     *   "emergencyId":       "uuid",
     *   "timestamp":         1753001234000,
     *   "lastKnownLocation": { "latitude": 11.1271, "longitude": 78.6569 } | null,
     *   "relayCount":        3,
     *   "relayHistory":      ["A", "B", "C"],
     *   "sensorTriggered":   true
     * }
     * </pre>
     */
    private JSONObject buildJsonPayload(SOSPacket packet) throws JSONException {
        JSONObject json = new JSONObject();

        json.put("emergencyId", packet.getEmergencyId());
        json.put("timestamp",   packet.getTimestamp());

        // Location — object if coordinates available, null if not.
        if (packet.getLatitude() != null && packet.getLongitude() != null) {
            JSONObject loc = new JSONObject();
            loc.put("latitude",  packet.getLatitude());
            loc.put("longitude", packet.getLongitude());
            json.put("lastKnownLocation", loc);
        } else {
            json.put("lastKnownLocation", JSONObject.NULL);
        }

        json.put("relayCount", packet.getHopCount());

        JSONArray history = new JSONArray();
        for (String id : packet.getRelayHistory()) history.put(id);
        json.put("relayHistory", history);

        json.put("sensorTriggered", packet.isSensorTriggered());

        return json;
    }
}
