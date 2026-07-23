package com.emergencyguardian.mesh;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SOSPacket — immutable data model for a single SOS alert propagated through the mesh.
 *
 * Fields:
 *   emergencyId     — unique identifier for this SOS event (UUID v4). Used for dedup.
 *   timestamp       — Unix epoch milliseconds at the moment of origin.
 *   originDeviceId  — Android ID of the device that first triggered the SOS.
 *   hopCount        — incremented by every relay node before forwarding.
 *   relayHistory    — ordered list of device IDs that have relayed this packet.
 *   location        — human-readable GPS string at the origin, or "Not Available". (P5)
 *   latitude        — raw latitude Double at origin, or null if unavailable.         (P6)
 *   longitude       — raw longitude Double at origin, or null if unavailable.        (P6)
 *   sensorTriggered — true when the SOS was fired by the ESP32 hardware sensor,      (P6)
 *                     false when triggered by the manual SOS button.
 *
 * Serialisation:
 *   toBytes() / fromBytes() use compact JSON over UTF-8 so the packet fits
 *   within a BLE GATT write (after MTU negotiation to 512 bytes).
 *
 * Backward compatibility:
 *   fromBytes() uses optString / optDouble / optBoolean for all fields added after
 *   P1, so packets produced by older builds deserialise cleanly with safe defaults.
 *
 * Packet size estimate (worst case, 10 hops, coords + sensor flag):
 *   ~340 bytes — within the 509-byte GATT value limit.
 */
public class SOSPacket {

    private static final String TAG = "SOSPacket";

    // ─── JSON keys ────────────────────────────────────────────────────────────
    private static final String KEY_ID       = "id";
    private static final String KEY_TS       = "ts";
    private static final String KEY_ORIGIN   = "origin";
    private static final String KEY_HOPS     = "hops";
    private static final String KEY_HISTORY  = "history";
    private static final String KEY_LOC      = "loc";        // P5: formatted string
    private static final String KEY_LAT      = "lat";        // P6: raw latitude
    private static final String KEY_LNG      = "lng";        // P6: raw longitude
    private static final String KEY_SENSOR   = "sensor";     // P6: sensorTriggered flag

    // ─── Fields ───────────────────────────────────────────────────────────────
    private final String       emergencyId;
    private final long         timestamp;
    private final String       originDeviceId;
    private final int          hopCount;
    private final List<String> relayHistory;
    private final String       location;        // P5
    private final Double       latitude;        // P6 — null if no GPS fix
    private final Double       longitude;       // P6 — null if no GPS fix
    private final boolean      sensorTriggered; // P6

    // ─── Constructor ──────────────────────────────────────────────────────────

    public SOSPacket(String       emergencyId,
                     long         timestamp,
                     String       originDeviceId,
                     int          hopCount,
                     List<String> relayHistory,
                     String       location,
                     Double       latitude,
                     Double       longitude,
                     boolean      sensorTriggered) {
        this.emergencyId     = emergencyId;
        this.timestamp       = timestamp;
        this.originDeviceId  = originDeviceId;
        this.hopCount        = hopCount;
        this.relayHistory    = new ArrayList<>(relayHistory);
        this.location        = (location != null && !location.isEmpty())
                               ? location : "Not Available";
        this.latitude        = latitude;
        this.longitude       = longitude;
        this.sensorTriggered = sensorTriggered;
    }

    // ─── Factory: create a brand-new SOS ──────────────────────────────────────

    /**
     * Creates a fresh SOS packet with full location data and trigger type.
     *
     * @param localDeviceId  Caller's Android device ID.
     * @param location       Formatted GPS string from LocationManager, or "Not Available".
     * @param latitude       Raw latitude, or null if no GPS fix.
     * @param longitude      Raw longitude, or null if no GPS fix.
     * @param sensorTriggered true if the SOS was fired by the ESP32 hardware sensor.
     */
    public static SOSPacket create(String  localDeviceId,
                                   String  location,
                                   Double  latitude,
                                   Double  longitude,
                                   boolean sensorTriggered) {
        return new SOSPacket(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                localDeviceId,
                0,
                new ArrayList<>(),
                location,
                latitude,
                longitude,
                sensorTriggered
        );
    }

    /**
     * Backward-compatible overload — location string only, no raw coords, button-triggered.
     * Prefer {@link #create(String, String, Double, Double, boolean)} for new callers.
     */
    public static SOSPacket create(String localDeviceId, String location) {
        return create(localDeviceId, location, null, null, false);
    }

    /**
     * Backward-compatible overload — no location, button-triggered.
     */
    public static SOSPacket create(String localDeviceId) {
        return create(localDeviceId, "Not Available", null, null, false);
    }

    // ─── Factory: build a relay copy with incremented hop ────────────────────

    /**
     * Returns a new SOSPacket with hopCount + 1 and this device appended to relayHistory.
     * The original packet is NOT mutated.
     *
     * Location, latitude, longitude, and sensorTriggered are preserved unchanged
     * — they always reflect the origin device's values, not the relay node's.
     *
     * @param relayingDeviceId The device ID of the relay node.
     */
    public SOSPacket withRelay(String relayingDeviceId) {
        List<String> newHistory = new ArrayList<>(relayHistory);
        newHistory.add(relayingDeviceId);
        return new SOSPacket(
                emergencyId,
                timestamp,
                originDeviceId,
                hopCount + 1,
                newHistory,
                location,
                latitude,
                longitude,
                sensorTriggered
        );
    }

    // ─── Serialisation ────────────────────────────────────────────────────────

    /**
     * Serialises this packet to a compact JSON byte array (UTF-8).
     * Returns null if serialisation fails (should never happen in practice).
     *
     * Raw lat/lng keys are omitted entirely when null (saves bytes, backward compat).
     */
    public byte[] toBytes() {
        try {
            JSONObject obj = new JSONObject();
            obj.put(KEY_ID,      emergencyId);
            obj.put(KEY_TS,      timestamp);
            obj.put(KEY_ORIGIN,  originDeviceId);
            obj.put(KEY_HOPS,    hopCount);
            obj.put(KEY_LOC,     location);
            obj.put(KEY_SENSOR,  sensorTriggered);

            // Omit lat/lng keys entirely when null — saves bytes, older builds ignore them.
            if (latitude  != null) obj.put(KEY_LAT, latitude);
            if (longitude != null) obj.put(KEY_LNG, longitude);

            JSONArray history = new JSONArray();
            for (String id : relayHistory) history.put(id);
            obj.put(KEY_HISTORY, history);

            return obj.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            Log.e(TAG, "toBytes() failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deserialises a byte array previously produced by {@link #toBytes()}.
     * Returns null if the bytes are malformed.
     *
     * All fields added after P1 use opt* methods with safe defaults so packets
     * from older builds deserialise cleanly.
     */
    public static SOSPacket fromBytes(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            String     json = new String(data, StandardCharsets.UTF_8);
            JSONObject obj  = new JSONObject(json);

            List<String> history = new ArrayList<>();
            JSONArray arr = obj.optJSONArray(KEY_HISTORY);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) history.add(arr.getString(i));
            }

            // P5 — location string (default "Not Available" for pre-P5 packets).
            String location = obj.optString(KEY_LOC, "Not Available");

            // P6 — raw coordinates (absent in pre-P6 packets → null).
            Double latitude  = obj.has(KEY_LAT) ? obj.getDouble(KEY_LAT) : null;
            Double longitude = obj.has(KEY_LNG) ? obj.getDouble(KEY_LNG) : null;

            // P6 — sensor trigger flag (default false for pre-P6 packets).
            boolean sensorTriggered = obj.optBoolean(KEY_SENSOR, false);

            return new SOSPacket(
                    obj.getString(KEY_ID),
                    obj.getLong(KEY_TS),
                    obj.getString(KEY_ORIGIN),
                    obj.getInt(KEY_HOPS),
                    history,
                    location,
                    latitude,
                    longitude,
                    sensorTriggered
            );
        } catch (JSONException e) {
            Log.e(TAG, "fromBytes() failed: " + e.getMessage());
            return null;
        }
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public String       getEmergencyId()    { return emergencyId;     }
    public long         getTimestamp()      { return timestamp;       }
    public String       getOriginDeviceId() { return originDeviceId;  }
    public int          getHopCount()       { return hopCount;        }
    public List<String> getRelayHistory()   { return new ArrayList<>(relayHistory); }
    public String       getLocation()       { return location;        }  // P5
    public Double       getLatitude()       { return latitude;        }  // P6
    public Double       getLongitude()      { return longitude;       }  // P6
    public boolean      isSensorTriggered() { return sensorTriggered; }  // P6

    @Override
    public String toString() {
        return "SOSPacket{id=" + emergencyId
                + ", origin=" + originDeviceId
                + ", hops=" + hopCount
                + ", location=" + location
                + ", sensor=" + sensorTriggered
                + ", history=" + relayHistory + "}";
    }
}
