package com.emergencyguardian;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

/**
 * BluetoothManager — provides Bluetooth adapter state information to the UI.
 *
 * Current responsibility:
 *   isBluetoothEnabled() — returns the live adapter on/off state so the
 *   status card in MainActivity can reflect it in real time.
 *
 * BLE scanning, GATT connections, and ESP32-C3 communication are handled
 * by {@link BLEManager}, which is separate from this class.
 */
public class BluetoothManager {

    private final BluetoothAdapter bluetoothAdapter;

    public BluetoothManager(Context context) {
        android.bluetooth.BluetoothManager systemBtManager =
                (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = (systemBtManager != null) ? systemBtManager.getAdapter() : null;
    }

    /**
     * Returns true if the device has a Bluetooth adapter and it is currently enabled.
     */
    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }
}
