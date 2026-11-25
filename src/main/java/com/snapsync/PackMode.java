package com.snapsync;

/**
 * When a snapshot is built.
 *
 * <p>Selected in {@code config/snapsync-common.toml} via {@code packMode}, so a pack can be
 * switched between "pack when quiet" and "pack on a clock" without touching code.
 */
public enum PackMode {

    /**
     * One-shot latch: builds at the first idle + healthy moment after being armed, then waits
     * for the next arming (server start, {@code /reload}, LAN publish, manual rebuild).
     *
     * <p>Good for public servers, where a repack during play would be wasted work and a repack
     * on a timer might land mid-session.
     */
    IDLE,

    /**
     * Fixed interval: builds every {@code intervalMinutes}, regardless of who is connected.
     *
     * <p>Ignores the idle gate on purpose - "every 15 minutes" that silently waits for an empty
     * server is not a timer. It also ignores the TPS gate, because building happens on a
     * background thread and a schedule that silently skips is harder to reason about than one
     * that is merely a little late. Overlapping builds are still prevented.
     *
     * <p>Good for private/always-busy servers where content changes on a known cadence.
     */
    INTERVAL
}
