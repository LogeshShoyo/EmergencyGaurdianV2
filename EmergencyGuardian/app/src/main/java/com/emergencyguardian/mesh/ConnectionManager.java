package com.emergencyguardian.mesh;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConnectionManager — manages both sides of BLE GATT communication:
 *
 *   SERVER side  (this device as peripheral / GATT server):
 *     - Hosts a GATT service with one WRITE+NOTIFY characteristic.
 *     - Peers connect to us and write SOSPacket bytes to the characteristic.
 *     - We can notify all connected centrals when we have a new packet.
 *
 *   CLIENT side  (this device as central / GATT client):
 *     - Connects to discovered peers' GATT servers.
 *     - Enables notifications on their SOS characteristic.
 *     - Writes SOSPacket bytes to their characteristic to relay our SOS.
 *
 * Packet flow:
 *   Outbound SOS  → writeToAllPeers()  → writes to every client GATT connection.
 *   Inbound SOS   → onCharacteristicWrite callback (server) → packetCallback.
 *
 * Reconnection:
 *   On GATT disconnection, a delayed reconnect is scheduled (RECONNECT_DELAY_MS).
 *   After MAX_RECONNECT_ATTEMPTS failures, the device is removed from the pool.
 */
public class ConnectionManager {

    private static final String TAG = "ConnectionManager";

    // ─── Callback interface ───────────────────────────────────────────────────

    /**
     * Notified when a raw SOSPacket arrives on the GATT server characteristic.
     */
    public interface PacketCallback {
        void onPacketReceived(byte[] data, String fromAddress);
    }

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final Context         context;
    private final PacketCallback  packetCallback;
    private final Handler         uiHandler = new Handler(Looper.getMainLooper());

    // GATT server (peripheral role).
    private BluetoothGattServer   gattServer;
    private BluetoothGattCharacteristic sosCharacteristic;

    // Track connected centrals (for notify).
    private final Set<BluetoothDevice> connectedCentrals =
            ConcurrentHashMap.newKeySet();

    // GATT clients (central role): address → BluetoothGatt.
    private final Map<String, BluetoothGatt> gattClients =
            new ConcurrentHashMap<>();

    // Reconnect attempt counters: address → count.
    private final Map<String, Integer> reconnectAttempts =
            new ConcurrentHashMap<>();

    // Devices we are mid-connecting (avoid duplicate connect calls).
    private final Set<String> connecting = ConcurrentHashMap.newKeySet();

    // ─── Constructor ──────────────────────────────────────────────────────────

    public ConnectionManager(Context context, PacketCallback packetCallback) {
        this.context        = context.getApplicationContext();
        this.packetCallback = packetCallback;
    }

    // ─── GATT Server (peripheral role) ───────────────────────────────────────

    /**
     * Opens a GATT server and registers the mesh service.
     * Must be called before any peer tries to connect.
     */
    public void startGattServer() {
        BluetoothManager bm =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            Log.e(TAG, "startGattServer() — BluetoothManager unavailable.");
            return;
        }

        gattServer = bm.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            Log.e(TAG, "startGattServer() — openGattServer returned null.");
            return;
        }

        // Build the GATT service.
        BluetoothGattService service = new BluetoothGattService(
                MeshConstants.SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        // SOS characteristic: WRITE (from central) + NOTIFY (to central).
        sosCharacteristic = new BluetoothGattCharacteristic(
                MeshConstants.CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        // CCCD descriptor — required for notifications.
        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                MeshConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE
        );
        sosCharacteristic.addDescriptor(cccd);
        service.addCharacteristic(sosCharacteristic);
        gattServer.addService(service);

        Log.d(TAG, "GATT server started. Service UUID=" + MeshConstants.SERVICE_UUID);
    }

    /** Closes the GATT server cleanly. */
    public void stopGattServer() {
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
            Log.d(TAG, "GATT server stopped.");
        }
    }

    // ─── GATT Client (central role) ───────────────────────────────────────────

    /**
     * Connects to the GATT server on a peer device.
     * Skips if already connected or connecting.
     *
     * @param device The peer discovered by DeviceDiscoveryManager.
     */
    public void connectToDevice(BluetoothDevice device) {
        String address = device.getAddress();

        if (gattClients.containsKey(address)) {
            Log.d(TAG, "Already connected to " + address + " — skipping.");
            return;
        }
        if (connecting.contains(address)) {
            Log.d(TAG, "Already connecting to " + address + " — skipping.");
            return;
        }
        if (gattClients.size() >= MeshConstants.MAX_CONNECTIONS) {
            Log.w(TAG, "Max connections reached (" + MeshConstants.MAX_CONNECTIONS
                    + "). Cannot connect to " + address + ".");
            return;
        }

        connecting.add(address);
        Log.d(TAG, "Connecting to " + address + "…");
        device.connectGatt(context, false /* autoConnect */, buildGattCallback(device),
                BluetoothDevice.TRANSPORT_LE);
    }

    /**
     * Writes an SOSPacket byte array to ALL currently connected peer GATT servers.
     *
     * @param data Serialised SOSPacket bytes.
     */
    public void writeToAllPeers(byte[] data) {
        if (gattClients.isEmpty()) {
            Log.d(TAG, "writeToAllPeers() — no peers connected.");
            return;
        }

        for (Map.Entry<String, BluetoothGatt> entry : gattClients.entrySet()) {
            String address = entry.getKey();
            BluetoothGatt gatt = entry.getValue();
            writeToPeer(gatt, address, data);
        }
    }

    /**
     * Sends a notification to all connected centrals (peripheral role).
     * Used when the server wants to push a packet to clients that have
     * subscribed to notifications on the SOS characteristic.
     *
     * @param data Serialised SOSPacket bytes.
     */
    public void notifyAllCentrals(byte[] data) {
        if (gattServer == null || sosCharacteristic == null) return;

        sosCharacteristic.setValue(data);
        for (BluetoothDevice central : connectedCentrals) {
            gattServer.notifyCharacteristicChanged(central, sosCharacteristic, false);
        }
        Log.d(TAG, "Notified " + connectedCentrals.size() + " connected central(s).");
    }

    /** Disconnects all GATT clients and closes their resources. */
    public void disconnectAll() {
        for (Map.Entry<String, BluetoothGatt> entry : gattClients.entrySet()) {
            entry.getValue().disconnect();
            entry.getValue().close();
            Log.d(TAG, "Disconnected from " + entry.getKey());
        }
        gattClients.clear();
        connecting.clear();
        reconnectAttempts.clear();
    }

    // ─── State queries ────────────────────────────────────────────────────────

    /** Returns the number of active outbound (client) connections. */
    public int getConnectedPeerCount() { return gattClients.size(); }

    /** Returns true if this device is connected to the given address. */
    public boolean isConnectedTo(String address) { return gattClients.containsKey(address); }

    // ─── Private GATT client write helper ────────────────────────────────────

    private void writeToPeer(BluetoothGatt gatt, String address, byte[] data) {
        BluetoothGattService service = gatt.getService(MeshConstants.SERVICE_UUID);
        if (service == null) {
            Log.w(TAG, "Peer " + address + " has no mesh service — skipping write.");
            return;
        }

        BluetoothGattCharacteristic ch = service.getCharacteristic(MeshConstants.CHARACTERISTIC_UUID);
        if (ch == null) {
            Log.w(TAG, "Peer " + address + " has no SOS characteristic — skipping write.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: new write method with explicit write type.
            gatt.writeCharacteristic(ch, data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            //noinspection deprecation
            ch.setValue(data);
            //noinspection deprecation
            gatt.writeCharacteristic(ch);
        }
        Log.d(TAG, "Relaying SOS to peer " + address + " (" + data.length + " bytes).");
    }

    // ─── GATT Client callback factory ─────────────────────────────────────────

    /**
     * Builds a BluetoothGattCallback that handles the full client lifecycle
     * for a specific device: connect → discover → subscribe → write/read → reconnect.
     */
    private BluetoothGattCallback buildGattCallback(BluetoothDevice device) {
        return new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                String address = device.getAddress();
                connecting.remove(address);

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to Device " + address);
                    gattClients.put(address, gatt);
                    reconnectAttempts.put(address, 0);
                    // Request larger MTU first; service discovery follows in onMtuChanged.
                    gatt.requestMtu(MeshConstants.PREFERRED_MTU);

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Disconnected from " + address + " (status=" + status + ").");
                    gattClients.remove(address);
                    gatt.close();
                    scheduleReconnect(device);
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                Log.d(TAG, "MTU negotiated: " + mtu + " for " + device.getAddress());
                // Proceed with service discovery after MTU negotiation.
                gatt.discoverServices();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Service discovery failed for " + device.getAddress()
                            + " status=" + status);
                    return;
                }

                BluetoothGattService service = gatt.getService(MeshConstants.SERVICE_UUID);
                if (service == null) {
                    Log.w(TAG, device.getAddress() + " does not expose mesh service — not a peer.");
                    gatt.disconnect();
                    return;
                }

                // Enable notifications on the SOS characteristic.
                BluetoothGattCharacteristic ch =
                        service.getCharacteristic(MeshConstants.CHARACTERISTIC_UUID);
                if (ch != null) {
                    gatt.setCharacteristicNotification(ch, true);
                    BluetoothGattDescriptor cccd = ch.getDescriptor(MeshConstants.CCCD_UUID);
                    if (cccd != null) {
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(cccd);
                    }
                }

                Log.d(TAG, "Services discovered for " + device.getAddress()
                        + ". Notifications enabled.");
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic) {
                // Received a notification from the peer's GATT server.
                byte[] data = characteristic.getValue();
                Log.d(TAG, "SOS Received (notify) from " + device.getAddress()
                        + " — " + (data != null ? data.length : 0) + " bytes.");
                if (packetCallback != null && data != null) {
                    packetCallback.onPacketReceived(data, device.getAddress());
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic characteristic,
                                              int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "SOS write confirmed by " + device.getAddress());
                } else {
                    Log.w(TAG, "SOS write to " + device.getAddress()
                            + " failed, status=" + status);
                }
            }
        };
    }

    // ─── GATT Server callback ─────────────────────────────────────────────────

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedCentrals.add(device);
                Log.d(TAG, "Central connected to our server: " + device.getAddress());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedCentrals.remove(device);
                Log.d(TAG, "Central disconnected from our server: " + device.getAddress());
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                 int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite,
                                                 boolean responseNeeded,
                                                 int offset,
                                                 byte[] value) {
            Log.d(TAG, "SOS Received (write) from " + device.getAddress()
                    + " — " + (value != null ? value.length : 0) + " bytes.");

            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }

            if (packetCallback != null && value != null) {
                byte[] copy = Arrays.copyOf(value, value.length);
                packetCallback.onPacketReceived(copy, device.getAddress());
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device,
                                             int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite,
                                             boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
            // Acknowledge the CCCD write that enables notifications.
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            Log.d(TAG, "CCCD write from " + device.getAddress()
                    + " — notifications "
                    + (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    ? "ENABLED" : "DISABLED"));
        }
    };

    // ─── Reconnection ─────────────────────────────────────────────────────────

    private void scheduleReconnect(BluetoothDevice device) {
        String address = device.getAddress();
        int attempts = reconnectAttempts.getOrDefault(address, 0);

        if (attempts >= MeshConstants.MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached for " + address + " — giving up.");
            reconnectAttempts.remove(address);
            return;
        }

        reconnectAttempts.put(address, attempts + 1);
        long delay = MeshConstants.RECONNECT_DELAY_MS * (attempts + 1); // Back-off.

        Log.d(TAG, "Scheduling reconnect to " + address
                + " in " + delay + " ms (attempt " + (attempts + 1) + ").");

        uiHandler.postDelayed(() -> {
            if (!gattClients.containsKey(address)) {
                Log.d(TAG, "Reconnecting to " + address + "…");
                connectToDevice(device);
            }
        }, delay);
    }
}
