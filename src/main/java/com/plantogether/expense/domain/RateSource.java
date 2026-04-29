package com.plantogether.expense.domain;

/**
 * Provenance of the FX rate captured when an expense is recorded.
 * Snapshot is frozen at entry time and never recomputed.
 */
public enum RateSource {
    /** Fetched live from the FX provider, or same-currency identity. */
    LIVE,
    /** Read from the TTL-bounded Redis cache (≤ 24h old). */
    CACHED,
    /** Provider unreachable; served from the unbounded last-known mirror. */
    FALLBACK
}
