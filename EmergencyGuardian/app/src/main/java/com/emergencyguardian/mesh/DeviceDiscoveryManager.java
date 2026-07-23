package com.emergencyguardian.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DeviceDiscoveryManager — handles BLE advertising and scanning so mesh peers
 * can find each other automatically without user interaction.
 *
 * Advertising:
 *   - Broadcasts the mesh SERVICE_UUID so peers can identify this app.
 *   - Uses LOW_POWER mode to conserve battery.
 *
 * Scanning:
 *   - Filters by SERVICE_UUID — only discovers other Emergency Guardian devices.
 *   - Uses LOW_LATENCY scan in BALANCED power mode.
 *   - Debounces duplicate discoveries; calls onDeviceFound once per address.
 *
 * Usage:
 *   1. Call startAdvertising() to make this device visible to peers.
 *   2. Call startScanning()    to begin looking for peers.
 *   3. Implement DiscoveryCallback to receive newly found devices.
 *   4. Call stop() when the activity pauses or the mesh is torn down.
 */
public class DeviceDiscoveryManager {

    private static final String TAG = "DeviceDiscoveryMgr";

    // ─── Callback interface ───────────────────────────────────────────────────

    public interface DiscoveryCallback {
        /**
         * Called (on the main thread) when a previously unseen peer is found.
         *
         * @param device  The discovered BluetoothDevice.
         * @param rssi    Signal strength (dBm) — can be used for proximity sorting.
         */
        void onDeviceFound(BluetoothDevice device, int rssi);
    }

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final Context           context;
    private final BluetoothAdapter  adapter;
    private final DiscoveryCallback callback;

    /** Set of MAC addresses already reported to the callback this session. */
    private final Set<String> discoveredAddresses =
            Collections.synchronizedSet(new HashSet<>());

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner    scanner;
    private boolean               isScanning    = false;
    private boolean               isAdvertising = false;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public DeviceDiscoveryManager(Context context,
                                  BluetoothAdapter adapter,
                                  DiscoveryCallback callback) {
        this.context  = context.getApplicationContext();
        this.adapter  = adapter;
        this.callback = callback;
    }

    // ─── Advertising ──────────────────────────────────────────────────────────

    /**
     * Begins BLE advertising so nearby peers can discover this device.
     * Safe to call multiple times; does nothing if already advertising.
     *
     * Requires: BLUETOOTH_ADVERTISE (API 31+) or BLUETOOTH (< API 31).
     */
    public void startAdvertising() {
        if (isAdvertising) return;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "startAdvertising() — Bluetooth not available.");
            return;
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(TAG, "startAdvertising() — device does not support BLE advertising.");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .setTimeout(0) // Advertise indefinitely.
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false) // Save payload space.
                .addServiceUuid(new ParcelUuid(MeshConstants.SERVICE_UUID))
                .build();

        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true) // Name in scan response (longer payload ok).
                .build();

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        Log.d(TAG, "Searching for nearby devices... (advertising started)");
    }

    /** Stops BLE advertising. */
    public void stopAdvertising() {
        if (!isAdvertising || advertiser == null) return;
        advertiser.stopAdvertising(advertiseCallback);
        isAdvertising = false;
        Log.d(TAG, "BLE advertising stopped.");
    }

    // ─── Scanning ────────────────────────────────────────────────────────────

    /**
     * Begins BLE scanning for peers advertising the mesh SERVICE_UUID.
     * Safe to call multiple times; does nothing if already scanning.
     *
     * Requires: BLUETOOTH_SCAN (API 31+) or ACCESS_FINE_LOCATION (< API 31).
     */
    public void startScanning() {
        if (isScanning) return;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "startScanning() — Bluetooth not available.");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.e(TAG, "startScanning() — scanner unavailable.");
            return;
        }

        // Filter: only pick up devices advertising our SERVICE_UUID.
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(MeshConstants.SERVICE_UUID))
                .build();

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(filter);

        scanner.startScan(filters, scanSettings, scanCallback);
        isScanning = true;
        Log.d(TAG, "Searching for nearby devices... (scanning started)");
    }

    /** Stops BLE scanning. */
    public void stopScanning() {
        if (!isScanning || scanner == null) return;
        scanner.stopScan(scanCallback);
        isScanning = false;
        Log.d(TAG, "BLE scanning stopped.");
    }

    /** Stops both advertising and scanning. Clears the discovered address cache. */
    public void stop() {
        stopAdvertising();
        stopScanning();
        discoveredAddresses.clear();
        Log.d(TAG, "DeviceDiscoveryManager stopped.");
    }

    // ─── State queries ────────────────────────────────────────────────────────

    public boolean isScanning()    { return isScanning;    }
    public boolean isAdvertising() { return isAdvertising; }

    // ─── BLE Callbacks ───────────────────────────────────────────────────────

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            Log.d(TAG, "BLE advertising started successfully.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            isAdvertising = false;
            Log.e(TAG, "BLE advertising failed, errorCode=" + errorCode
                    + " (" + advertiseErrorString(errorCode) + ")");
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            String address = device.getAddress();

            // Debounce: report each device only once per scan session.
            if (discoveredAddresses.contains(address)) return;
            discoveredAddresses.add(address);

            // Verify it's actually advertising our service (double-check after filter).
            ScanRecord record = result.getScanRecord();
            if (record != null) {
                List<ParcelUuid> uuids = record.getServiceUuids();
                if (uuids == null || !uuids.contains(new ParcelUuid(MeshConstants.SERVICE_UUID))) {
                    return;
                }
            }

            Log.d(TAG, "Connected to Device " + address + " (RSSI=" + result.getRssi() + " dBm)");
            callback.onDeviceFound(device, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            Log.e(TAG, "BLE scan failed, errorCode=" + errorCode);
        }
    };

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String advertiseErrorString(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "ALREADY_STARTED";
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "DATA_TOO_LARGE";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "FEATURE_UNSUPPORTED";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "TOO_MANY_ADVERTISERS";
            default:
                return "UNKNOWN";
        }
    }
}
