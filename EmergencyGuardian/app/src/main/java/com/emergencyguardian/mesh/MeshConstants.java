package com.emergencyguardian.mesh;

import java.util.UUID;

/**
 * MeshConstants — shared UUIDs and configuration constants for the BLE mesh layer.
 *
 * All devices in the mesh must use the same UUIDs. These values are intentionally
 * kept in one place so a future protocol version can change them consistently.
 *
 * UUID prefix: 0000xxxx-0000-1000-8000-00805F9B34FB (Bluetooth base UUID form)
 * We use the EG (Emergency Guardian) custom 128-bit UUIDs to avoid collisions
 * with standard Bluetooth services.
 */
public final class MeshConstants {

    private MeshConstants() { /* utility class */ }

    // ─── BLE Service & Characteristic UUIDs ──────────────────────────────────

    /**
     * GATT Service UUID — identifies this app's mesh service.
     * Devices scan for this UUID; only peers advertising it are considered.
     */
    public static final UUID SERVICE_UUID =
            UUID.fromString("0000E701-0000-1000-8000-00805F9B34FB");

    /**
     * GATT Characteristic UUID — used for both WRITE (client→server) and
     * NOTIFY (server→client) of SOSPacket byte arrays.
     */
    public static final UUID CHARACTERISTIC_UUID =
            UUID.fromString("0000E702-0000-1000-8000-00805F9B34FB");

    /**
     * Client Characteristic Configuration Descriptor (CCCD) UUID.
     * Standard 16-bit UUID 0x2902 — required to enable BLE notifications.
     */
    public static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    // ─── BLE Advertising ──────────────────────────────────────────────────────

    /**
     * Local name broadcast in BLE advertisements.
     * Kept short (≤8 bytes) to fit within the 31-byte advertising payload.
     */
    public static final String ADVERTISE_LOCAL_NAME = "EG_MESH";

    /** How long (ms) to scan for peers in each scan window. 0 = scan indefinitely. */
    public static final long SCAN_DURATION_MS = 0;

    // ─── Connection ───────────────────────────────────────────────────────────

    /** Maximum number of simultaneous GATT client connections (Android limit ~7). */
    public static final int MAX_CONNECTIONS = 5;

    /**
     * Preferred MTU size (bytes). After negotiation, allows SOS packets up to
     * ~509 bytes (MTU − 3 byte ATT header) — comfortably fits our JSON payload.
     */
    public static final int PREFERRED_MTU = 512;

    /** Reconnect delay (ms) after a GATT disconnection. */
    public static final long RECONNECT_DELAY_MS = 5_000L;

    /** Maximum reconnect attempts per device before giving up. */
    public static final int MAX_RECONNECT_ATTEMPTS = 5;

    // ─── Packet ───────────────────────────────────────────────────────────────

    /** Maximum hop count before a packet is dropped (loop prevention). */
    public static final int MAX_HOP_COUNT = 20;
}
