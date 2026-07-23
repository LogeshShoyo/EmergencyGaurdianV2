package com.emergencyguardian;

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.emergencyguardian.mesh.MeshManager;
import com.emergencyguardian.mesh.SOSPacket;

/**
 * MainActivity — the single screen of Emergency Guardian.
 *
 * Responsibilities:
 *   1. Request runtime permissions via PermissionManager.
 *   2. Check Bluetooth and Location state on resume; show dialogs if disabled.
 *   3. Wire the SOS button press-and-hold gesture to SOSManager.
 *   4. Update status UI in response to SOSManager events.
 *   5. Periodically refresh Bluetooth / Location status text.
 *   6. Start/stop the BLE mesh layer via MeshManager (lifecycle only).
 *
 * ── What was NOT changed from Prototype 1 ────────────────────────────────────
 *   • SOS button layout and drawable are unchanged.
 *   • The 5-second countdown logic lives entirely in SOSManager.
 *   • triggerSOS() still only does Log.d + updates status text.
 *   • onEmergencySignalReceived() is unchanged.
 *   • All dialog and status-polling code is unchanged.
 *
 * ── What is new in Prototype 2 ────────────────────────────────────────────────
 *   • MeshManager is initialised in onCreate() and started/stopped with the activity.
 *   • When triggerSOS() fires (inside onSOSTriggered()), meshManager.sendSOS() is
 *     called to propagate the alert to nearby peers.
 *   • MeshListener callbacks update the "Peers" count in the status card.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class MainActivity extends AppCompatActivity
        implements SOSManager.SOSListener, MeshManager.MeshListener, BLEManager.BLEListener {

    private static final String TAG = "MainActivity";

    // ─── Constants ────────────────────────────────────────────────────────────

    /** How often to re-check Bluetooth / Location status (ms). */
    private static final long STATUS_POLL_INTERVAL_MS = 2_000L;

    // ─── Managers ────────────────────────────────────────────────────────────

    private BluetoothManager  bluetoothManager;
    private LocationManager   locationManager;
    private PermissionManager permissionManager;
    private SOSManager        sosManager;
    private MeshManager       meshManager;      // ← NEW in Prototype 2
    private BLEManager        bleManager;       // ← NEW in Prototype 3 — ESP32-C3 BLE
    private EmergencyMessenger emergencyMessenger; // ← NEW in Prototype 4 — message sending
    private NetworkManager     networkManager;     // ← NEW in Prototype 5 — internet detection
    private ServerManager      serverManager;      // ← NEW in Prototype 6 — SOS upload

    // ─── UI references ────────────────────────────────────────────────────────

    private View     btnSOS;
    private TextView tvStatus;
    private TextView tvCountdown;
    private TextView tvBluetoothStatus;
    private TextView tvLocationStatus;
    private TextView tvEsp32Status;     // ← NEW in Prototype 3

    // ─── State ────────────────────────────────────────────────────────────────

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable statusPollRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatusCard();
            uiHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS);
        }
    };

    private boolean bluetoothDialogShown  = false;
    private boolean locationDialogShown   = false;
    /** Set to true when the SOS was triggered by the ESP32 sensor rather than the button. */
    private boolean pendingSensorTrigger  = false; // ← NEW in Prototype 6

    // ─── Activity lifecycle ───────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Bind views.
        btnSOS            = findViewById(R.id.btnSOS);
        tvStatus          = findViewById(R.id.tvStatus);
        tvCountdown       = findViewById(R.id.tvCountdown);
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus);
        tvLocationStatus  = findViewById(R.id.tvLocationStatus);
        tvEsp32Status     = findViewById(R.id.tvEsp32Status);   // ← NEW

        // Initialise managers.
        bluetoothManager  = new BluetoothManager(this);
        locationManager   = new LocationManager(this);
        permissionManager = new PermissionManager(this);
        sosManager        = new SOSManager(this);
        meshManager       = new MeshManager(this, this);   // ← NEW in Prototype 2
        bleManager          = new BLEManager(this, this);      // ← NEW in Prototype 3
        emergencyMessenger  = new EmergencyMessenger(this);    // ← NEW in Prototype 4
        networkManager      = new NetworkManager(this);         // ← NEW in Prototype 5
        serverManager       = new ServerManager(this);          // ← NEW in Prototype 6

        // Request all required permissions, then check hardware state.
        permissionManager.checkAndRequestAll(allGranted -> {
            updateStatusCard();
            checkAndPromptBluetooth();
            checkAndPromptLocation();
            // Start the mesh only after permissions are resolved.
            meshManager.start();                           // ← NEW in Prototype 2
            // Start ESP32-C3 BLE scan only after permissions are resolved.
            bleManager.startScan();                        // ← NEW in Prototype 3
            // Seed location cache and subscribe to passive updates. ← NEW in Prototype 5
            // Listener pipes every new fix directly into EmergencyMessenger so the
            // emergency message body always shows the freshest known position.
            locationManager.startLocationUpdates(
                    str -> emergencyMessenger.setLastKnownLocation(str));
        });

        setupSOSButton();
    }

    @Override
    protected void onResume() {
        super.onResume();

        bluetoothDialogShown = false;
        locationDialogShown  = false;

        uiHandler.post(statusPollRunnable);
        checkAndPromptBluetooth();
        checkAndPromptLocation();

        // Resume mesh if it was stopped on pause.
        if (!meshManager.isStarted()) meshManager.start();                      // ← Prototype 2
        // Resume ESP32-C3 scan if not already scanning or connected.
        if (!bleManager.isConnected() && !bleManager.isScanning()) {           // ← Prototype 3
            bleManager.startScan();
        }
        // Resume passive location updates (stopped in onPause). ← Prototype 5
        locationManager.startLocationUpdates(
                str -> emergencyMessenger.setLastKnownLocation(str));
    }

    @Override
    protected void onPause() {
        super.onPause();

        uiHandler.removeCallbacks(statusPollRunnable);
        sosManager.cancelCountdown();
        resetSOSUI();

        meshManager.stop();                                  // ← Prototype 2
        bleManager.stop();                                   // ← Prototype 3
        locationManager.stopLocationUpdates();               // ← Prototype 5
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionManager.handleResult(requestCode, permissions, grantResults);
    }

    // ─── SOS Button setup (UNCHANGED from Prototype 1) ───────────────────────

    @SuppressWarnings("ClickableViewAccessibility")
    private void setupSOSButton() {
        btnSOS.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.performClick();
                    startSOSSequence();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (sosManager.isRunning()) {
                        sosManager.cancelCountdown();
                    }
                    return true;
            }
            return false;
        });
        btnSOS.setOnClickListener(v -> { /* accessibility path */ });
    }

    private void startSOSSequence() {
        pendingSensorTrigger = false; // ← P6: button press always clears sensor flag
        tvStatus.setText(getString(R.string.status_holding));
        tvStatus.setTextColor(getColor(R.color.status_warning));
        tvCountdown.setVisibility(View.VISIBLE);
        tvCountdown.setText("5");
        sosManager.startCountdown();
    }

    // ─── SOSManager.SOSListener (UNCHANGED from Prototype 1) ─────────────────

    @Override
    public void onCountdownTick(int secondsRemaining) {
        tvCountdown.setText(String.valueOf(secondsRemaining));
    }

    /**
     * Called when the 5-second hold (or ESP32 simulation) completes.
     *
     * Prototype 1: Log.d("SOS", "SOS Triggered") + update status text.
     * Prototype 2: also broadcasts the SOS through the mesh. ← NEW
     */
    @Override
    public void onSOSTriggered() {
        // ── Prototype 1 behaviour (unchanged) ──
        Log.d("SOS", "SOS Triggered");
        tvCountdown.setVisibility(View.INVISIBLE);
        tvStatus.setText(getString(R.string.status_sos_triggered));
        tvStatus.setTextColor(getColor(R.color.status_error));

        // ── Prototype 5: capture last known location for this SOS event ──
        String location = locationManager.getLastKnownLocationString();
        Double lat      = locationManager.getLastLatitude();
        Double lng      = locationManager.getLastLongitude();
        Log.d(TAG, "SOS location: " + location);

        // ── Prototype 5: check and log internet connectivity at SOS origin ──
        networkManager.checkAndLogStatus();

        // ── Prototype 6: read and reset the sensor trigger flag ──
        boolean sensorTriggered = pendingSensorTrigger;
        pendingSensorTrigger = false;

        // ── Prototype 2: broadcast through mesh (now with full location + trigger type) ──
        if (meshManager.isStarted()) {
            SOSPacket sent = meshManager.sendSOS(location, lat, lng, sensorTriggered);
            Log.d(TAG, "SOS pushed to mesh: " + sent);
            // ── Prototype 6: upload to server — ServerManager checks internet internally ──
            serverManager.uploadSOS(sent);
        } else {
            Log.w(TAG, "Mesh not running — SOS not propagated.");
        }

        // ── Prototype 4: build and send emergency message ──
        // Location is already in EmergencyMessenger via the setLastKnownLocation()
        // listener wired in startLocationUpdates() — no extra call needed here.
        emergencyMessenger.sendEmergencyMessage();  // relay count = 0 (this device is origin)
    }

    @Override
    public void onCountdownCancelled() {
        resetSOSUI();
    }

    // ─── MeshManager.MeshListener (NEW in Prototype 2) ───────────────────────

    @Override
    public void onPeerDiscovered(String address) {
        Log.d(TAG, "Connected to Device " + address);
        // Optional: update a peer count badge in the UI here in a future sprint.
    }

    @Override
    public void onSOSReceived(SOSPacket packet) {
        Log.d(TAG, "SOS Received from mesh — " + packet);
        // The status text is intentionally NOT changed here: the existing SOS
        // button UI belongs to Prototype 1 and is managed by SOSManager.
        // A future sprint can add a separate "incoming alert" banner.
    }

    @Override
    public void onUploadReady(SOSPacket packet) {
        Log.i(TAG, "Internet Available. SOS Ready To Upload. id=" + packet.getEmergencyId());
        serverManager.uploadSOS(packet);  // ← NEW Prototype 6 — relay node uploads to server
    }

    @Override
    public void onDuplicateIgnored(String emergencyId) {
        Log.d(TAG, "Duplicate SOS Ignored — id=" + emergencyId);
    }

    // ─── Status card helpers (UNCHANGED from Prototype 1) ────────────────────

    private void updateStatusCard() {
        boolean btEnabled  = bluetoothManager.isBluetoothEnabled();
        boolean locEnabled = locationManager.isLocationEnabled();

        if (btEnabled) {
            tvBluetoothStatus.setText("Enabled");
            tvBluetoothStatus.setTextColor(getColor(R.color.status_ok));
        } else {
            tvBluetoothStatus.setText("Disabled");
            tvBluetoothStatus.setTextColor(getColor(R.color.status_error));
        }

        if (locEnabled) {
            tvLocationStatus.setText("Enabled");
            tvLocationStatus.setTextColor(getColor(R.color.status_ok));
        } else {
            tvLocationStatus.setText("Disabled");
            tvLocationStatus.setTextColor(getColor(R.color.status_error));
        }
    }

    private void resetSOSUI() {
        tvCountdown.setVisibility(View.INVISIBLE);
        tvCountdown.setText("");
        if (!getString(R.string.status_sos_triggered).equals(tvStatus.getText().toString())) {
            tvStatus.setText(getString(R.string.status_waiting));
            tvStatus.setTextColor(getColor(R.color.colorOnBackground));
        }
    }

    // ─── Bluetooth / Location prompts (UNCHANGED from Prototype 1) ───────────

    private void checkAndPromptBluetooth() {
        if (bluetoothDialogShown || bluetoothManager.isBluetoothEnabled()) return;
        bluetoothDialogShown = true;

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_bluetooth_title))
                .setMessage(getString(R.string.dialog_bluetooth_message))
                .setCancelable(true)
                .setPositiveButton(getString(R.string.dialog_open_settings),
                        (dialog, which) -> {
                            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                            startActivity(intent);
                        })
                .setNegativeButton(getString(R.string.dialog_dismiss), null)
                .show();
    }

    private void checkAndPromptLocation() {
        if (locationDialogShown || locationManager.isLocationEnabled()) return;
        locationDialogShown = true;

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_location_title))
                .setMessage(getString(R.string.dialog_location_message))
                .setCancelable(true)
                .setPositiveButton(getString(R.string.dialog_open_settings),
                        (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            startActivity(intent);
                        })
                .setNegativeButton(getString(R.string.dialog_dismiss), null)
                .show();
    }

    // ─── BLEManager.BLEListener (NEW in Prototype 3) ─────────────────────────

    /**
     * Updates the "ESP32:" row in the status card when the BLE connection state changes.
     * Called on the main thread by BLEManager.
     */
    @Override
    public void onConnectionStatusChanged(BLEManager.ConnectionStatus status) {
        switch (status) {
            case SCANNING:
                tvEsp32Status.setText(getString(R.string.esp32_status_scanning));
                tvEsp32Status.setTextColor(getColor(R.color.status_warning));
                Log.d(TAG, "ESP32 BLE: Scanning...");
                break;
            case CONNECTED:
                tvEsp32Status.setText(getString(R.string.esp32_status_connected));
                tvEsp32Status.setTextColor(getColor(R.color.status_ok));
                Log.d(TAG, "ESP32 BLE: Connected");
                break;
            case DISCONNECTED:
                tvEsp32Status.setText(getString(R.string.esp32_status_disconnected));
                tvEsp32Status.setTextColor(getColor(R.color.status_error));
                Log.d(TAG, "ESP32 BLE: Disconnected");
                break;
        }
    }

    // ─── ESP32 / BLEManager signal entry point ────────────────────────────────

    /**
     * Called by BLEManager when the ESP32-C3 sends an emergency notification,
     * AND manually as a test hook (unchanged from Prototype 1).
     *
     * Starts the identical 5-second countdown as the SOS button.
     * At the end, onSOSTriggered() fires — which also calls meshManager.sendSOS().
     */
    public void onEmergencySignalReceived() {
        pendingSensorTrigger = true; // ← P6: mark this SOS as hardware-sensor-triggered
        tvStatus.setText(getString(R.string.status_holding));
        tvStatus.setTextColor(getColor(R.color.status_warning));
        tvCountdown.setVisibility(View.VISIBLE);
        tvCountdown.setText("5");
        sosManager.onEmergencySignalReceived();
    }
}
