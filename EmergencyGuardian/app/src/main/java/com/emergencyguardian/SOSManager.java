package com.emergencyguardian;

import android.os.CountDownTimer;
import android.util.Log;

/**
 * SOSManager — controls the SOS press-and-hold countdown and trigger logic.
 *
 * Responsibilities:
 *   • Run a 5-second countdown when the SOS button is held (or when an
 *     emergency signal is received from the ESP32 via onEmergencySignalReceived()).
 *   • Publish tick / finish / cancel events to the UI via {@link SOSListener}.
 *   • Call triggerSOS() when the countdown completes.
 *
 * Design notes:
 *   • CountDownTimer runs on the main thread, so UI callbacks are safe.
 *   • cancelCountdown() is idempotent — safe to call even if no timer is running.
 */
public class SOSManager {

    private static final String TAG = "SOS";

    /** Total hold duration in milliseconds. */
    private static final long COUNTDOWN_DURATION_MS = 5_000L;

    /** Interval between ticks in milliseconds. */
    private static final long COUNTDOWN_INTERVAL_MS = 1_000L;

    // ─── Listener interface ───────────────────────────────────────────────────

    /**
     * Callback interface implemented by MainActivity to update the UI.
     */
    public interface SOSListener {
        /**
         * Called once per second with the remaining whole seconds.
         * Values: 5, 4, 3, 2, 1.
         */
        void onCountdownTick(int secondsRemaining);

        /**
         * Called when the countdown finishes (5 s elapsed) and SOS has been triggered.
         */
        void onSOSTriggered();

        /**
         * Called when the countdown is cancelled before completion
         * (e.g. user lifted the button).
         */
        void onCountdownCancelled();
    }

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final SOSListener listener;
    private CountDownTimer countDownTimer;
    private boolean isRunning = false;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public SOSManager(SOSListener listener) {
        this.listener = listener;
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Starts (or restarts) the 5-second countdown.
     *
     * If a countdown is already running it is cancelled first, then a fresh one
     * is started. This prevents duplicate timers when the user quickly presses
     * the button multiple times.
     */
    public void startCountdown() {
        if (isRunning) {
            cancelCountdownInternal();
        }

        Log.d(TAG, "Countdown started.");
        isRunning = true;

        countDownTimer = new CountDownTimer(COUNTDOWN_DURATION_MS, COUNTDOWN_INTERVAL_MS) {

            @Override
            public void onTick(long millisUntilFinished) {
                // millisUntilFinished counts down from 5000 → 1000.
                // We want to show: 5, 4, 3, 2, 1.
                int secondsLeft = (int) Math.ceil(millisUntilFinished / 1000.0);
                Log.d(TAG, "Countdown tick: " + secondsLeft);
                listener.onCountdownTick(secondsLeft);
            }

            @Override
            public void onFinish() {
                isRunning = false;
                triggerSOS();
            }
        }.start();
    }

    /**
     * Cancels the countdown if it is currently running and notifies the listener.
     * Safe to call when no countdown is active.
     */
    public void cancelCountdown() {
        if (!isRunning) return;
        cancelCountdownInternal();
        Log.d(TAG, "Countdown cancelled by user.");
        listener.onCountdownCancelled();
    }

    /**
     * Returns true if the countdown is currently running.
     */
    public boolean isRunning() {
        return isRunning;
    }

    // ─── ESP32 signal simulation ───────────────────────────────────────────────

    /**
     * Simulates receiving an emergency signal from the ESP32-C3 over Bluetooth.
     *
     * When called, this method starts exactly the same 5-second countdown as the
     * SOS button hold. At the end of the countdown {@link #triggerSOS()} is called.
     *
     * To test this in a future sprint, call it from a debug menu, a test button,
     * or a BLE notification callback once the Bluetooth layer is implemented.
     */
    public void onEmergencySignalReceived() {
        Log.d(TAG, "onEmergencySignalReceived() — simulating ESP32 signal, starting countdown.");
        startCountdown();
    }

    // ─── Core SOS trigger ─────────────────────────────────────────────────────

    /**
     * The SOS trigger action.
     *
     * Current implementation:
     *   • Logs "SOS Triggered" to Logcat.
     *   • Notifies the UI listener so the status text can be updated.
     *
     * Future implementation (NOT yet):
     *   • Send SMS to emergency contacts.
     *   • Broadcast over Bluetooth Mesh / ESP32.
     *   • Upload location to a server.
     *   • Trigger an audible alarm.
     */
    private void triggerSOS() {
        Log.d(TAG, "SOS Triggered");
        listener.onSOSTriggered();
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /** Cancels the timer and resets the running flag without notifying the listener. */
    private void cancelCountdownInternal() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        isRunning = false;
    }
}
