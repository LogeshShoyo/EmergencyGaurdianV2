package com.emergencyguardian;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Collections;
import java.util.UUID;

/**
 * BLEManager — manages the BLE connection between the Android app and the owner's ESP32-C3.
 *
 * Responsibilities:
 *   1. Scan for the ESP32-C3 by its advertised service UUID.
 *   2. Auto-connect when the device is discovered.
 *   3. Enable notifications on the emergency characteristic.
 *   4. Call {@link BLEListener#onEmergencySignalReceived()} when the ESP32 sends an alert.
 *   5. Auto-reconnect if the connection drops, with exponential back-off.
 *   6. Report status changes (Scanning / Connected / Disconnected) to the listener.
 *
 * ── How to match UUIDs in your ESP32-C3 firmware ─────────────────────────────
 *   The ESP32 must advertise ESP32_SERVICE_UUID and expose a characteristic
 *   with UUID ESP32_EMERGENCY_CHAR_UUID that sends a BLE notification when an
 *   emergency signal is triggered.
 *
 *   Update the two UUID constants below to match your firmware exactly.
 *   If your firmware uses 16-bit UUIDs, use the full 128-bit form:
 *     0000XXXX-0000-1000-8000-00805F9B34FB
 *
 * ── What is NOT in this class ────────────────────────────────────────────────
 *   • Mesh networking (handled by MeshManager / ConnectionManager)
 *   • SMS, server uploads, GPS, notifications
 *   • Any UI logic — status updates go through BLEListener only
 */
public class BLEManager {

    private static final String TAG = "BLEManager";

    // ─── ESP32 BLE UUIDs ─────────────────────────────────────────────────────
    //
    // !! IMPORTANT: Update these to match your ESP32-C3 firmware !!
    //
    // ESP32_SERVICE_UUID       — the primary service the ESP32 advertises.
    // ESP32_EMERGENCY_CHAR_UUID— the characteristic that sends a notification
    //                            when the hardware button / trigger fires.
    // ─────────────────────────────────────────────────────────────────────────

    public static final UUID ESP32_SERVICE_UUID =
            UUID.fromString("00001000-0000-1000-8000-00805F9B34FB");

    public static final UUID ESP32_EMERGENCY_CHAR_UUID =
            UUID.fromString("00001001-0000-1000-8000-00805F9B34FB");

    /** Standard Client Characteristic Configuration Descriptor — enables notifications. */
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    // ─── Reconnection policy ──────────────────────────────────────────────────

    private static final int  MAX_RECONNECT_ATTEMPTS = 10;
    private static final long BASE_RECONNECT_DELAY_MS = 3_000L;   // 3 s base
    private static final long MAX_RECONNECT_DELAY_MS  = 60_000L;  // 1 min cap

    // ─── Listener interface ───────────────────────────────────────────────────

    /**
     * Implemented by MainActivity to receive BLE events on the main thread.
     */
    public interface BLEListener {

        /** Called when the BLE connection status changes. */
        void onConnectionStatusChanged(ConnectionStatus status);

        /**
         * Called when the ESP32-C3 sends an emergency notification.
         * The implementation should start the 5-second SOS countdown.
         */
        void onEmergencySignalReceived();
    }

    // ─── Connection status enum ───────────────────────────────────────────────

    public enum ConnectionStatus {
        SCANNING,
        CONNECTED,
        DISCONNECTED
    }

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final Context     context;
    private final BLEListener listener;
    private final Handler     uiHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter   bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt      gatt;
    private BluetoothDevice    targetDevice;   // Device discovered during scan.

    private boolean scanning    = false;
    private boolean connected   = false;
    private boolean stopping    = false;       // Set when stop() is called; suppresses reconnect.

    private int  reconnectAttempts = 0;

    // ─── Constructor ──────────────────────────────────────────────────────────

    /**
     * @param context  Any context; application context is used internally.
     * @param listener Receives connection state changes and emergency signals.
     *                 All callbacks fire on the main thread.
     */
    public BLEManager(Context context, BLEListener listener) {
        this.context  = context.getApplicationContext();
        this.listener = listener;

        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager)
                        context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) bluetoothAdapter = bm.getAdapter();
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Starts BLE scanning for the ESP32-C3.
     *
     * Safe to call when already scanning or connected — extra calls are ignored.
     * Call from Activity.onResume() after permissions are confirmed.
     */
    public void startScan() {
        if (scanning || connected) return;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "startScan() — Bluetooth not available.");
            return;
        }

        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) {
            Log.e(TAG, "startScan() — BLE scanner unavailable.");
            return;
        }

        stopping = false;

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(ESP32_SERVICE_UUID))
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build();

        leScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        scanning = true;

        Log.d(TAG, "Scanning...");
        notifyStatus(ConnectionStatus.SCANNING);
    }

    /**
     * Stops scanning and disconnects any active GATT connection.
     *
     * Call from Activity.onPause() or when the user navigates away.
     * Suppresses automatic reconnection.
     */
    public void stop() {
        stopping = true;
        stopScan();
        disconnectGatt();
        reconnectAttempts = 0;
        Log.d(TAG, "BLEManager stopped.");
    }

    /** Returns true if a BLE scan is currently in progress. */
    public boolean isScanning() { return scanning; }

    /** Returns true if the GATT connection to the ESP32-C3 is active. */
    public boolean isConnected() { return connected; }

    // ─── BLE Scan ─────────────────────────────────────────────────────────────

    private void stopScan() {
        if (!scanning || leScanner == null) return;
        leScanner.stopScan(scanCallback);
        scanning = false;
        Log.d(TAG, "Scan stopped.");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            Log.d(TAG, "ESP32-C3 found: " + device.getAddress()
                    + " (RSSI=" + result.getRssi() + " dBm)");

            // Stop scanning — we have our target; connect immediately.
            stopScan();
            targetDevice = device;
            connectGatt(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            Log.e(TAG, "Scan failed, errorCode=" + errorCode);
        }
    };

    // ─── GATT Connection ──────────────────────────────────────────────────────

    private void connectGatt(BluetoothDevice device) {
        Log.d(TAG, "Connecting to ESP32-C3 at " + device.getAddress() + "…");
        // autoConnect=false for a faster initial connection.
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void disconnectGatt() {
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        connected = false;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        // ── Connection state ──────────────────────────────────────────────────

        @Override
        public void onConnectionStateChange(BluetoothGatt gattConn, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                reconnectAttempts = 0;

                Log.d(TAG, "Connected to ESP32-C3. Discovering services…");
                notifyStatus(ConnectionStatus.CONNECTED);

                // Request a larger MTU then discover services.
                gattConn.requestMtu(256);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                Log.d(TAG, "Disconnected from ESP32-C3 (status=" + status + ").");

                if (gatt != null) {
                    gatt.close();
                    gatt = null;
                }

                notifyStatus(ConnectionStatus.DISCONNECTED);

                if (!stopping) {
                    scheduleReconnect();
                }
            }
        }

        // ── MTU negotiated → discover services ────────────────────────────────

        @Override
        public void onMtuChanged(BluetoothGatt gattConn, int mtu, int status) {
            Log.d(TAG, "MTU negotiated: " + mtu);
            gattConn.discoverServices();
        }

        // ── Services discovered → enable notifications ─────────────────────────

        @Override
        public void onServicesDiscovered(BluetoothGatt gattConn, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed, status=" + status);
                return;
            }

            BluetoothGattService service = gattConn.getService(ESP32_SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "ESP32 service not found. Check ESP32_SERVICE_UUID.");
                return;
            }

            BluetoothGattCharacteristic emergencyChar =
                    service.getCharacteristic(ESP32_EMERGENCY_CHAR_UUID);
            if (emergencyChar == null) {
                Log.e(TAG, "Emergency characteristic not found. Check ESP32_EMERGENCY_CHAR_UUID.");
                return;
            }

            // Enable local notification delivery.
            gattConn.setCharacteristicNotification(emergencyChar, true);

            // Write the CCCD descriptor to tell the ESP32 to send notifications.
            BluetoothGattDescriptor cccd = emergencyChar.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattConn.writeDescriptor(cccd,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    //noinspection deprecation
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    //noinspection deprecation
                    gattConn.writeDescriptor(cccd);
                }
                Log.d(TAG, "Notifications enabled on emergency characteristic.");
            } else {
                Log.w(TAG, "CCCD descriptor not found — notifications may not arrive.");
            }
        }

        // ── Notification received from ESP32 ──────────────────────────────────

        /**
         * Called (on a BLE callback thread) when the ESP32-C3 sends a notification
         * on the emergency characteristic. Dispatches to the main thread and calls
         * the listener so MainActivity can start the 5-second SOS countdown.
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gattConn,
                                            BluetoothGattCharacteristic characteristic) {
            if (!ESP32_EMERGENCY_CHAR_UUID.equals(characteristic.getUuid())) return;

            Log.d(TAG, "Emergency signal received from ESP32-C3.");
            uiHandler.post(() -> {
                if (listener != null) listener.onEmergencySignalReceived();
            });
        }

        // API 33+ override (same logic, new signature).
        @Override
        public void onCharacteristicChanged(BluetoothGatt gattConn,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            onCharacteristicChanged(gattConn, characteristic);
        }
    };

    // ─── Reconnection ─────────────────────────────────────────────────────────

    /**
     * Schedules a reconnect attempt with exponential back-off.
     * After MAX_RECONNECT_ATTEMPTS the device falls back to a new BLE scan
     * so it can pick up the ESP32-C3 if its MAC address has rotated.
     */
    private void scheduleReconnect() {
        reconnectAttempts++;

        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached — restarting scan.");
            reconnectAttempts = 0;
            targetDevice = null;
            uiHandler.postDelayed(this::startScan, BASE_RECONNECT_DELAY_MS);
            return;
        }

        // Exponential back-off: 3 s, 6 s, 12 s … capped at 60 s.
        long delay = Math.min(
                BASE_RECONNECT_DELAY_MS * (1L << (reconnectAttempts - 1)),
                MAX_RECONNECT_DELAY_MS
        );

        Log.d(TAG, "Reconnecting in " + delay + " ms (attempt " + reconnectAttempts + ").");
        uiHandler.postDelayed(() -> {
            if (stopping || connected) return;
            if (targetDevice != null) {
                connectGatt(targetDevice);   // Re-use last known device.
            } else {
                startScan();                 // Fallback: scan again.
            }
        }, delay);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Posts a status change to the listener on the main thread. */
    private void notifyStatus(ConnectionStatus status) {
        uiHandler.post(() -> {
            if (listener != null) listener.onConnectionStatusChanged(status);
        });
    }
}
