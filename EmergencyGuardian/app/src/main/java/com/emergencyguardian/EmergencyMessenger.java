package com.emergencyguardian;

import android.app.Activity;
import android.app.AlertDialog;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * EmergencyMessenger — builds and dispatches the human-readable SOS alert message.
 *
 * ── Responsibilities ──────────────────────────────────────────────────────────
 *   1. Build a formatted emergency message string via buildEmergencyMessage().
 *   2. Log the message to Logcat.
 *   3. Display the message on screen (AlertDialog).
 *   4. Pass the message to every registered DeliveryChannel.
 *
 * ── Extensibility (DeliveryChannel) ──────────────────────────────────────────
 *   To add SMS, internet upload, or mesh relay later:
 *     1. Implement DeliveryChannel.
 *     2. Call emergencyMessenger.addDeliveryChannel(new SMSDeliveryChannel(...)).
 *   No other class needs to change. The core build/send logic is untouched.
 *
 * ── What is NOT done here ─────────────────────────────────────────────────────
 *   • No actual SMS is sent.
 *   • No internet request is made.
 *   • No GPS — location shows "Not Available" until GPS is implemented and
 *     a LocationProvider is injected (see getLastKnownLocation()).
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 *   sendEmergencyMessage() must be called on the main thread (it shows a dialog).
 *   It is always called from onSOSTriggered(), which runs on the main thread.
 */
public class EmergencyMessenger {

    private static final String TAG = "EmergencyMessenger";

    // ─── Emergency contact ────────────────────────────────────────────────────

    /**
     * The configured emergency contact number.
     *
     * Stored here as the single source of truth — never hardcoded elsewhere.
     * Replace with a user-configurable value in a future settings screen.
     */
    public static final String EMERGENCY_CONTACT = "8778628911";

    // ─── ID format ────────────────────────────────────────────────────────────

    /** Prefix for the human-readable emergency ID shown in the message. */
    private static final String ID_PREFIX = "EG";

    /** Date/time format used in the message body. */
    private static final String TIME_FORMAT = "yyyy-MM-dd HH:mm:ss z";

    // ─── DeliveryChannel — extensibility interface ─────────────────────────────

    /**
     * A single delivery mechanism for the emergency message.
     *
     * Implement this interface to add SMS, server API, or mesh relay:
     *
     * <pre>
     * class SMSDeliveryChannel implements DeliveryChannel {
     *     {@literal @}Override public String getName() { return "SMS"; }
     *     {@literal @}Override public void deliver(String contact, String message) {
     *         SmsManager.getDefault().sendTextMessage(contact, null, message, null, null);
     *     }
     * }
     * emergencyMessenger.addDeliveryChannel(new SMSDeliveryChannel());
     * </pre>
     */
    public interface DeliveryChannel {

        /** Short name used in log output (e.g. "SMS", "HTTP", "Mesh"). */
        String getName();

        /**
         * Send or relay the emergency message.
         *
         * @param contact The destination (phone number, URL, device ID, etc.).
         * @param message The full message string built by buildEmergencyMessage().
         */
        void deliver(String contact, String message);
    }

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final Activity            activity;
    private final List<DeliveryChannel> deliveryChannels = new ArrayList<>();

    /**
     * Sequential counter incremented on every sendEmergencyMessage() call.
     * Produces IDs like EG-0001, EG-0002, etc.
     * Resets on app restart — persistence can be added later via SharedPreferences.
     */
    private int messageSequence = 0;

    /**
     * Location string injected from the outside.
     * "Not Available" until a LocationProvider is connected.
     * Call setLastKnownLocation() when GPS is implemented.
     */
    private String lastKnownLocation = "Not Available";

    // ─── Constructor ──────────────────────────────────────────────────────────

    /**
     * @param activity Used to show the on-screen message dialog (main thread only).
     */
    public EmergencyMessenger(Activity activity) {
        this.activity = activity;
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Builds a human-readable emergency message string.
     *
     * Called internally by sendEmergencyMessage(), but also useful for previewing
     * the message in a settings screen or unit test.
     *
     * @param emergencyId  The human-readable ID for this event (e.g. "EG-0001").
     * @param relayCount   Number of mesh hops this signal has traveled (0 = origin).
     * @return             The fully formatted message text.
     */
    public String buildEmergencyMessage(String emergencyId, int relayCount) {
        String timestamp = new SimpleDateFormat(TIME_FORMAT, Locale.getDefault())
                .format(new Date());

        return "🚨 EMERGENCY SOS\n"
                + "\n"
                + "Emergency ID: " + emergencyId + "\n"
                + "\n"
                + "Possible structural emergency detected.\n"
                + "\n"
                + "Last Known Location:\n"
                + lastKnownLocation + "\n"
                + "\n"
                + "Time:\n"
                + timestamp + "\n"
                + "\n"
                + "Relay Count:\n"
                + relayCount + "\n"
                + "\n"
                + "This is an automatically generated emergency message.";
    }

    /**
     * Builds the emergency message, logs it, displays it on screen, and passes
     * it to every registered DeliveryChannel.
     *
     * Call this whenever an SOS is confirmed (button hold or ESP32 signal).
     *
     * @param relayCount Number of mesh hops (0 when this device is the origin).
     */
    public void sendEmergencyMessage(int relayCount) {
        messageSequence++;
        String emergencyId = String.format(Locale.US, "%s-%04d", ID_PREFIX, messageSequence);
        String message     = buildEmergencyMessage(emergencyId, relayCount);

        // 1. Log to Logcat.
        Log.i(TAG, "──────────────────────────────────────────");
        Log.i(TAG, message);
        Log.i(TAG, "Contact: " + EMERGENCY_CONTACT);
        Log.i(TAG, "──────────────────────────────────────────");

        // 2. Display on screen.
        showMessageDialog(emergencyId, message);

        // 3. Pass to every registered DeliveryChannel (none registered yet in
        //    this prototype — ready for SMS, HTTP, mesh relay to be plugged in).
        if (deliveryChannels.isEmpty()) {
            Log.d(TAG, "No delivery channels registered — message logged only.");
        } else {
            for (DeliveryChannel channel : deliveryChannels) {
                try {
                    Log.d(TAG, "Delivering via " + channel.getName() + " to " + EMERGENCY_CONTACT);
                    channel.deliver(EMERGENCY_CONTACT, message);
                } catch (Exception e) {
                    Log.e(TAG, "DeliveryChannel '" + channel.getName() + "' failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Convenience overload — uses relay count 0 (this device is the SOS origin).
     */
    public void sendEmergencyMessage() {
        sendEmergencyMessage(0);
    }

    // ─── Delivery channel management ──────────────────────────────────────────

    /**
     * Registers a new delivery channel.
     *
     * To add SMS in a future sprint:
     * <pre>
     *   emergencyMessenger.addDeliveryChannel(new SMSDeliveryChannel(context));
     * </pre>
     *
     * @param channel The channel to register. Must not be null.
     */
    public void addDeliveryChannel(DeliveryChannel channel) {
        if (channel == null) return;
        deliveryChannels.add(channel);
        Log.d(TAG, "DeliveryChannel registered: " + channel.getName());
    }

    /**
     * Removes a previously registered delivery channel by name.
     *
     * @param name The name returned by {@link DeliveryChannel#getName()}.
     */
    public void removeDeliveryChannel(String name) {
        deliveryChannels.removeIf(ch -> ch.getName().equals(name));
        Log.d(TAG, "DeliveryChannel removed: " + name);
    }

    // ─── Location injection ───────────────────────────────────────────────────

    /**
     * Sets the location string shown in the message body.
     *
     * Call this from a future GPS integration when a fix is available:
     * <pre>
     *   emergencyMessenger.setLastKnownLocation("37.7749° N, 122.4194° W");
     * </pre>
     *
     * Pass null or empty string to revert to "Not Available".
     *
     * @param location Human-readable location string, or null to reset.
     */
    public void setLastKnownLocation(String location) {
        lastKnownLocation = (location != null && !location.isEmpty())
                ? location
                : "Not Available";
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Shows a scrollable AlertDialog containing the full emergency message.
     *
     * Uses a programmatically created ScrollView → TextView so the dialog
     * can display the complete message regardless of screen size.
     */
    private void showMessageDialog(String emergencyId, String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        // Build a scrollable TextView for the message body.
        TextView messageView = new TextView(activity);
        messageView.setText(message);
        messageView.setTextSize(14f);
        messageView.setPadding(48, 32, 48, 32);
        messageView.setTextIsSelectable(true);  // Allows copy.

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(messageView);

        new AlertDialog.Builder(activity)
                .setTitle("🚨 Emergency Message — " + emergencyId)
                .setView(scrollView)
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .show();
    }
}
