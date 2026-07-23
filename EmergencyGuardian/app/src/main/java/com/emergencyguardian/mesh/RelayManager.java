package com.emergencyguardian.mesh;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RelayManager — decides whether an incoming SOS should be forwarded or ignored.
 *
 * Responsibilities:
 *   1. Deduplication  — maintains a time-limited cache of already-seen emergency IDs.
 *   2. Internet check — inspects ConnectivityManager for validated internet access.
 *   3. Relay decision — returns an action for every incoming packet.
 *
 * Thread-safety:
 *   All public methods are synchronised; safe to call from multiple BLE callback threads.
 */
public class RelayManager {

    private static final String TAG = "RelayManager";

    // ─── Constants ────────────────────────────────────────────────────────────

    /** Maximum number of IDs kept in the dedup cache. */
    private static final int CACHE_MAX_SIZE = 256;

    /**
     * How long (ms) a seen emergency ID is remembered.
     * 30 minutes — well beyond any realistic mesh propagation window.
     */
    private static final long CACHE_TTL_MS = 30 * 60 * 1_000L;

    /** Maximum number of hops an SOS packet is allowed to travel. */
    public static final int MAX_HOP_COUNT = 20;

    // ─── Relay actions ────────────────────────────────────────────────────────

    public enum RelayAction {
        /** Packet is new; relay it to peers. */
        RELAY,
        /** Packet was already seen; drop it silently. */
        IGNORE_DUPLICATE,
        /** Hop limit exceeded; drop to prevent infinite loops. */
        IGNORE_HOP_LIMIT,
        /** Internet is available; ready to upload instead of relaying. */
        UPLOAD_READY
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private final Context context;

    /**
     * LRU cache: emergencyId → System.currentTimeMillis() when first seen.
     * Bounded to CACHE_MAX_SIZE; oldest entries evicted first.
     */
    private final Map<String, Long> seenIds = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(CACHE_MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            }
    );

    // ─── Constructor ──────────────────────────────────────────────────────────

    public RelayManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Evaluates an incoming SOSPacket and returns the appropriate action.
     *
     * Call this every time a packet is received (from user trigger or mesh peer).
     * The method marks the packet as seen on the first encounter.
     *
     * @param packet The received SOS packet.
     * @return The action this device should take.
     */
    public synchronized RelayAction evaluate(SOSPacket packet) {
        // 1. Hop limit guard.
        if (packet.getHopCount() >= MAX_HOP_COUNT) {
            Log.w(TAG, "Hop limit reached for SOS " + packet.getEmergencyId()
                    + " (" + packet.getHopCount() + " hops). Dropping.");
            return RelayAction.IGNORE_HOP_LIMIT;
        }

        // 2. Deduplication.
        String id = packet.getEmergencyId();
        Long firstSeen = seenIds.get(id);
        long now = System.currentTimeMillis();

        if (firstSeen != null) {
            // Check TTL — if the entry has expired, treat as new.
            if ((now - firstSeen) < CACHE_TTL_MS) {
                Log.d(TAG, "Duplicate SOS Ignored — id=" + id);
                return RelayAction.IGNORE_DUPLICATE;
            } else {
                // Expired entry; allow re-processing and refresh timestamp.
                Log.d(TAG, "Expired cache entry for SOS " + id + " — re-processing.");
            }
        }

        // 3. Mark as seen.
        seenIds.put(id, now);
        Log.d(TAG, "SOS Received — " + packet);

        // 4. Internet check.
        if (isInternetAvailable()) {
            Log.i(TAG, "Internet Available. SOS Ready To Upload. id=" + id);
            return RelayAction.UPLOAD_READY;
        }

        // 5. No internet — relay to peers.
        Log.d(TAG, "Relaying SOS — id=" + id + ", hops=" + packet.getHopCount());
        return RelayAction.RELAY;
    }

    /**
     * Marks an emergency ID as seen without evaluating relay logic.
     * Use this when this device is the SOS origin (to prevent receiving
     * its own broadcast back from a peer and re-processing it).
     *
     * @param emergencyId The ID to pre-register.
     */
    public synchronized void markAsSeen(String emergencyId) {
        seenIds.put(emergencyId, System.currentTimeMillis());
        Log.d(TAG, "Marked as seen (origin): " + emergencyId);
    }

    /**
     * Returns true if the device currently has a validated internet connection
     * (i.e. ConnectivityManager reports INTERNET + VALIDATED capabilities).
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
     * Clears the dedup cache. Useful for testing or on app restart.
     */
    public synchronized void clearCache() {
        seenIds.clear();
        Log.d(TAG, "Dedup cache cleared.");
    }

    /** Returns the number of IDs currently in the dedup cache. */
    public synchronized int getCacheSize() {
        return seenIds.size();
    }
}
