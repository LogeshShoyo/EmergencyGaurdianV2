package com.emergencyguardian.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * MeshManager — the single public facade for the entire BLE mesh layer.
 *
 * Coordinates:
 *   DeviceDiscoveryManager — advertising + scanning (who is nearby?).
 *   ConnectionManager      — GATT server + client connections.
 *   RelayManager           — deduplication + relay decision logic.
 *
 * Lifecycle:
 *   Call start() when the app becomes visible (Activity.onResume()).
 *   Call stop()  when the app goes to the background (Activity.onPause()).
 *   Call sendSOS() when the user triggers the SOS button or the ESP32 fires.
 *
 * The existing SOS button and UI in MainActivity are NOT modified by this class.
 * MeshManager receives a MeshListener callback and the UI wires that up separately.
 *
 * Thread safety:
 *   All public methods are main-thread safe. BLE callbacks are dispatched
 *   back onto the main thread before invoking the listener.
 */
public class MeshManager
        implements DeviceDiscoveryManager.DiscoveryCallback,
                   ConnectionManager.PacketCallback {

    private static final String TAG = "MeshManager";

    // ─── Listener interface ───────────────────────────────────────────────────

    /**
     * Optional callback for the UI layer. Every method is called on the main thread.
     */
    public interface MeshListener {
        /** A new peer has been discovered and a connection is being attempted. */
        void onPeerDiscovered(String address);

        /** An incoming SOS packet has passed dedup and is being relayed. */
        void onSOSReceived(SOSPacket packet);

        /**
         * A received SOS packet has reached a device with internet.
         * Ready to upload but not yet doing so (upload is a future sprint).
         */
        void onUploadReady(SOSPacket packet);

        /** A duplicate SOS packet was ignored. */
        void onDuplicateIgnored(String emergencyId);
    }

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final Context    context;
    private final String     localDeviceId;
    private final Handler    uiHandler = new Handler(Looper.getMainLooper());

    private MeshListener           listener;
    private DeviceDiscoveryManager discoveryManager;
    private ConnectionManager      connectionManager;
    private RelayManager           relayManager;

    private boolean started = false;

    // ─── Constructor ──────────────────────────────────────────────────────────

    /**
     * @param context Any context; the application context is used internally.
     * @param listener Optional UI callback. Pass null if not needed.
     */
    public MeshManager(Context context, MeshListener listener) {
        this.context       = context.getApplicationContext();
        this.listener      = listener;
        this.localDeviceId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.d(TAG, "MeshManager initialised. Local device ID: " + localDeviceId);
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Starts the mesh: opens the GATT server, begins advertising, begins scanning.
     * Safe to call multiple times — subsequent calls are ignored if already started.
     *
     * Call from Activity.onResume() after runtime permissions have been granted.
     */
    public void start() {
        if (started) {
            Log.d(TAG, "start() called but mesh is already running.");
            return;
        }

        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "start() — Bluetooth not available. Mesh will not start.");
            return;
        }

        // Initialise sub-managers.
        relayManager      = new RelayManager(context);
        connectionManager = new ConnectionManager(context, this /* PacketCallback */);
        discoveryManager  = new DeviceDiscoveryManager(context, adapter, this /* DiscoveryCallback */);

        // Open our GATT server so peers can connect to us.
        connectionManager.startGattServer();

        // Advertise so peers can find us; scan so we can find peers.
        discoveryManager.startAdvertising();
        discoveryManager.startScanning();

        started = true;
        Log.d(TAG, "Mesh started. Searching for nearby devices...");
    }

    /**
     * Stops the mesh: stops advertising, scanning, disconnects all peers,
     * closes the GATT server.
     *
     * Call from Activity.onPause() or when the app goes to the background.
     */
    public void stop() {
        if (!started) return;

        if (discoveryManager  != null) discoveryManager.stop();
        if (connectionManager != null) {
            connectionManager.disconnectAll();
            connectionManager.stopGattServer();
        }

        started = false;
        Log.d(TAG, "Mesh stopped.");
    }

    // ─── Public API: send SOS ─────────────────────────────────────────────────

    /**
     * Creates a fresh SOSPacket (with location) and broadcasts it to all connected peers.
     *
     * Call this from MainActivity when the user completes the 5-second hold
     * (inside onSOSTriggered()) or when onEmergencySignalReceived() fires.
     * The existing SOS button logic in MainActivity is NOT changed here.
     *
     * @param location Human-readable GPS string from LocationManager, e.g.
     *                 "12.3456° N, 98.7654° E", or "Not Available". ← NEW P5
     * @return The SOSPacket that was created and sent (for logging / UI).
     */
    /**
     * Primary factory — creates a packet with full location and trigger type,
     * then broadcasts to all connected mesh peers.
     *
     * @param location       Formatted GPS string ("12.34° N, 56.78° E" or "Not Available").
     * @param latitude       Raw latitude from LocationManager, or null if unavailable.
     * @param longitude      Raw longitude from LocationManager, or null if unavailable.
     * @param sensorTriggered true when the SOS was fired by the ESP32 hardware sensor.
     */
    public SOSPacket sendSOS(String location,                             // ← updated P6
                             Double latitude,
                             Double longitude,
                             boolean sensorTriggered) {
        SOSPacket packet = SOSPacket.create(
                localDeviceId, location, latitude, longitude, sensorTriggered);

        // Mark it seen immediately so we don't relay our own broadcast if it
        // bounces back from a peer.
        relayManager.markAsSeen(packet.getEmergencyId());

        byte[] data = packet.toBytes();
        if (data == null) {
            Log.e(TAG, "sendSOS() — failed to serialise packet.");
            return packet;
        }

        Log.d(TAG, "SOS Received — origin is this device. Location: " + location
                + ", sensor=" + sensorTriggered
                + ". Sending to " + connectionManager.getConnectedPeerCount()
                + " peer(s). " + packet);

        connectionManager.writeToAllPeers(data);
        connectionManager.notifyAllCentrals(data);

        return packet;
    }

    /**
     * Convenience overload — location string only (no raw coords, button-triggered).
     * Prefer the full overload when LocationManager has lat/lng values.
     */
    public SOSPacket sendSOS(String location) {
        return sendSOS(location, null, null, false);
    }

    /**
     * Backward-compatible no-arg overload — "Not Available" location, button-triggered.
     */
    public SOSPacket sendSOS() {
        return sendSOS("Not Available", null, null, false);
    }

    // ─── DeviceDiscoveryManager.DiscoveryCallback ─────────────────────────────

    /**
     * Called when a new peer advertising our mesh UUID is found.
     * Automatically initiates a GATT connection — no user interaction needed.
     */
    @Override
    public void onDeviceFound(BluetoothDevice device, int rssi) {
        String address = device.getAddress();
        Log.d(TAG, "Connected to Device " + address + " (RSSI=" + rssi + " dBm)");

        // Attempt GATT connection immediately.
        connectionManager.connectToDevice(device);

        // Notify the UI layer.
        notifyOnMain(() -> {
            if (listener != null) listener.onPeerDiscovered(address);
        });
    }

    // ─── ConnectionManager.PacketCallback ────────────────────────────────────

    /**
     * Called whenever raw bytes arrive on either:
     *   - Our GATT server characteristic (a peer wrote an SOS to us), or
     *   - A GATT client notification (a peer pushed an SOS to us).
     *
     * This is where the relay decision is made.
     */
    @Override
    public void onPacketReceived(byte[] data, String fromAddress) {
        SOSPacket incoming = SOSPacket.fromBytes(data);
        if (incoming == null) {
            Log.w(TAG, "onPacketReceived() — could not parse packet from " + fromAddress);
            return;
        }

        Log.d(TAG, "SOS Received from " + fromAddress + " — " + incoming);

        RelayManager.RelayAction action = relayManager.evaluate(incoming);

        switch (action) {

            case RELAY: {
                // Build the relay copy with incremented hop count.
                SOSPacket relay = incoming.withRelay(localDeviceId);
                byte[] relayData = relay.toBytes();

                if (relayData != null) {
                    Log.d(TAG, "Relaying SOS — id=" + relay.getEmergencyId()
                            + ", hops=" + relay.getHopCount());
                    connectionManager.writeToAllPeers(relayData);
                    connectionManager.notifyAllCentrals(relayData);
                }

                final SOSPacket finalRelay = relay;
                notifyOnMain(() -> {
                    if (listener != null) listener.onSOSReceived(finalRelay);
                });
                break;
            }

            case UPLOAD_READY: {
                // Internet is available — log and notify; do NOT upload yet.
                Log.i(TAG, "Internet Available. SOS Ready To Upload. id="
                        + incoming.getEmergencyId());
                final SOSPacket finalPacket = incoming;
                notifyOnMain(() -> {
                    if (listener != null) listener.onUploadReady(finalPacket);
                });
                break;
            }

            case IGNORE_DUPLICATE: {
                Log.d(TAG, "Duplicate SOS Ignored — id=" + incoming.getEmergencyId());
                final String id = incoming.getEmergencyId();
                notifyOnMain(() -> {
                    if (listener != null) listener.onDuplicateIgnored(id);
                });
                break;
            }

            case IGNORE_HOP_LIMIT: {
                // Already logged inside RelayManager.
                break;
            }
        }
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /** Returns the number of devices currently connected as GATT peers. */
    public int getConnectedPeerCount() {
        return connectionManager != null ? connectionManager.getConnectedPeerCount() : 0;
    }

    /** Returns true if the mesh is currently running. */
    public boolean isStarted() { return started; }

    /** Replaces the current MeshListener. Pass null to remove. */
    public void setListener(MeshListener listener) { this.listener = listener; }

    /** Returns this device's stable ID (Android ID). */
    public String getLocalDeviceId() { return localDeviceId; }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private BluetoothAdapter getBluetoothAdapter() {
        BluetoothManager bm =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    private void notifyOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            uiHandler.post(r);
        }
    }
}
