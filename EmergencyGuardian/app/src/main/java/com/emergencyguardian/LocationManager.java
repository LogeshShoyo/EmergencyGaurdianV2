package com.emergencyguardian;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * LocationManager — obtains and caches the device's last known GPS location.
 *
 * ── Strategy (battery-friendly, not continuous tracking) ──────────────────────
 *   1. startLocationUpdates(): immediately call getLastKnownLocation() on both
 *      NETWORK and GPS providers — zero power cost, instant result.
 *   2. Register a passive LocationListener (minTime=30 s, minDistance=50 m) on
 *      both providers so that if the OS delivers a new fix while the app is
 *      foregrounded, the cache is updated automatically.
 *   3. stopLocationUpdates(): unregister immediately — called from onPause().
 *
 * ── What this class does NOT do ───────────────────────────────────────────────
 *   • Does NOT run continuous high-frequency GPS tracking.
 *   • Does NOT use Google Play Services FusedLocationProvider (no dependency).
 *   • Does NOT show maps or geofencing.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 *   Location callbacks are delivered on the main looper (Looper.getMainLooper()
 *   is passed to requestLocationUpdates). getLastKnownLocationString() is safe
 *   to call from the main thread.
 *
 * ── Permissions required ──────────────────────────────────────────────────────
 *   ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION (both declared in manifest).
 *   If neither is granted, startLocationUpdates() logs a warning and returns.
 *
 * NOTE: This class is named LocationManager (same as android.location.LocationManager).
 *       The system class is referenced using its fully-qualified path throughout.
 */
public class LocationManager {

    private static final String TAG = "LocationManager";

    /** Minimum time between OS-delivered updates (ms). 30 s — conserves battery. */
    private static final long  MIN_TIME_MS     = 30_000L;

    /** Minimum distance between OS-delivered updates (metres). */
    private static final float MIN_DISTANCE_M  = 50f;

    /** String returned when no GPS fix is available. */
    public static final String LOCATION_UNAVAILABLE = "Not Available";

    // ─── LocationUpdateListener ───────────────────────────────────────────────

    /**
     * Callback invoked on the main thread whenever a new location is cached.
     *
     * In MainActivity this is wired to EmergencyMessenger.setLastKnownLocation()
     * so the emergency message body always reflects the freshest known position:
     * <pre>
     *   locationManager.startLocationUpdates(
     *       str -> emergencyMessenger.setLastKnownLocation(str));
     * </pre>
     */
    public interface LocationUpdateListener {
        /**
         * @param locationString Human-readable formatted coordinates,
         *                       e.g. "12.3456° N, 98.7654° E", or LOCATION_UNAVAILABLE.
         */
        void onLocationUpdated(String locationString);
    }

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final Context                            context;
    private final android.location.LocationManager  systemLm;

    private Double                 lastLatitude     = null;
    private Double                 lastLongitude    = null;
    private boolean                updatesActive    = false;
    private LocationUpdateListener updateListener   = null;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public LocationManager(Context context) {
        this.context  = context.getApplicationContext();
        this.systemLm = (android.location.LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
    }

    // ─── isLocationEnabled (UNCHANGED from Prototype 1) ──────────────────────

    /**
     * Returns true if the device has at least one location provider (GPS or Network)
     * currently enabled in Android Settings.
     */
    public boolean isLocationEnabled() {
        if (systemLm == null) return false;
        boolean gpsEnabled     = false;
        boolean networkEnabled = false;
        try {
            gpsEnabled = systemLm.isProviderEnabled(
                    android.location.LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) { }
        try {
            networkEnabled = systemLm.isProviderEnabled(
                    android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) { }
        return gpsEnabled || networkEnabled;
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Seeds the cache from the last known fix (instant, zero power) and registers
     * a passive listener for updates while the app is foregrounded.
     *
     * Safe to call multiple times — re-entrant calls while updates are already
     * active are ignored. Call from Activity.onResume() after permissions are granted.
     *
     * @param listener Notified on the main thread whenever the cache is updated.
     *                 Pass null to disable notifications (cache still updates silently).
     */
    public void startLocationUpdates(LocationUpdateListener listener) {
        this.updateListener = listener;

        if (!hasPermission()) {
            Log.w(TAG, "Location permission not granted — updates not started.");
            // Notify listener once with unavailable so EmergencyMessenger is initialised.
            if (listener != null) listener.onLocationUpdated(LOCATION_UNAVAILABLE);
            return;
        }
        if (systemLm == null) {
            Log.e(TAG, "System LocationManager unavailable.");
            return;
        }
        if (updatesActive) return;

        // 1. Immediate seed — getLastKnownLocation() costs zero power.
        seedFromLastKnown();

        // 2. Passive subscription — fires only when OS has a new fix ready.
        registerProvider(android.location.LocationManager.NETWORK_PROVIDER); // fast
        registerProvider(android.location.LocationManager.GPS_PROVIDER);      // accurate

        updatesActive = true;
        Log.d(TAG, "Location updates started. Current: " + getLastKnownLocationString());
    }

    /**
     * Unregisters the location listener. Call from Activity.onPause() to stop
     * receiving updates when the app is backgrounded.
     */
    public void stopLocationUpdates() {
        if (!updatesActive) return;
        if (systemLm != null && hasPermission()) {
            try {
                systemLm.removeUpdates(locationListener);
            } catch (Exception ignored) { }
        }
        updatesActive = false;
        Log.d(TAG, "Location updates stopped.");
    }

    /**
     * Returns the last known location as a human-readable string.
     *
     * Format: "12.3456° N, 98.7654° E"
     * Returns {@link #LOCATION_UNAVAILABLE} if no fix has been obtained yet
     * or if location permission was denied.
     */
    public String getLastKnownLocationString() {
        if (lastLatitude == null || lastLongitude == null) return LOCATION_UNAVAILABLE;

        String latDir = lastLatitude  >= 0 ? "N" : "S";
        String lngDir = lastLongitude >= 0 ? "E" : "W";
        return String.format(Locale.US,
                "%.4f° %s, %.4f° %s",
                Math.abs(lastLatitude),  latDir,
                Math.abs(lastLongitude), lngDir);
    }

    /**
     * Returns the raw last-known latitude, or null if no fix is available.
     * Provided for future use (e.g. attaching coords to server upload payload).
     */
    public Double getLastLatitude()  { return lastLatitude;  }

    /**
     * Returns the raw last-known longitude, or null if no fix is available.
     */
    public Double getLastLongitude() { return lastLongitude; }

    /** Returns true if at least one GPS fix has been cached. */
    public boolean hasLocation() {
        return lastLatitude != null && lastLongitude != null;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Tries to seed the cache immediately from both providers' last-known fix.
     * GPS is tried second so it overwrites network if both are available
     * (GPS is more accurate).
     */
    private void seedFromLastKnown() {
        tryGetLastKnown(android.location.LocationManager.NETWORK_PROVIDER);
        tryGetLastKnown(android.location.LocationManager.GPS_PROVIDER);
    }

    @SuppressWarnings("MissingPermission")
    private void tryGetLastKnown(String provider) {
        try {
            if (!systemLm.isProviderEnabled(provider)) return;
            Location loc = systemLm.getLastKnownLocation(provider);
            if (loc != null) {
                Log.d(TAG, "Seeded from last-known (" + provider + "): "
                        + loc.getLatitude() + ", " + loc.getLongitude());
                updateCache(loc);
            }
        } catch (Exception e) {
            Log.w(TAG, "tryGetLastKnown(" + provider + ") failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("MissingPermission")
    private void registerProvider(String provider) {
        try {
            if (!systemLm.isProviderEnabled(provider)) return;
            systemLm.requestLocationUpdates(
                    provider,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()   // Callbacks on main thread.
            );
            Log.d(TAG, "Listening for updates from " + provider + ".");
        } catch (Exception e) {
            Log.w(TAG, "registerProvider(" + provider + ") failed: " + e.getMessage());
        }
    }

    /** Stores the new fix and notifies the listener. Always called on main thread. */
    private void updateCache(Location location) {
        lastLatitude  = location.getLatitude();
        lastLongitude = location.getLongitude();
        String formatted = getLastKnownLocationString();
        Log.d(TAG, "Location updated: " + formatted
                + " (accuracy=" + String.format(Locale.US, "%.1f", location.getAccuracy()) + " m)");
        if (updateListener != null) updateListener.onLocationUpdated(formatted);
    }

    private boolean hasPermission() {
        int fine   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION);
        int coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION);
        return fine   == PackageManager.PERMISSION_GRANTED
            || coarse == PackageManager.PERMISSION_GRANTED;
    }

    // ─── android.location.LocationListener ───────────────────────────────────

    private final LocationListener locationListener = new LocationListener() {

        @Override
        public void onLocationChanged(Location location) {
            // Already on main thread (Looper.getMainLooper() passed at registration).
            updateCache(location);
        }

        /** Deprecated in API 29 — kept for pre-29 compatibility (minSdk = 29, safe to stub). */
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) { }

        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }
    };
}
