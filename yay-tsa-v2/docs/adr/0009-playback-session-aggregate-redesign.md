# ADR 0009 - PlaybackSession Aggregate Redesign

**Status:** Accepted

## Context

The v1 PlaybackSession aggregate stored the current playback position by continuously updating a `position_ms` column, generating excessive writes. It also conflated runtime device state with persistent aggregate state and included a rating field that added complexity without clear value. The lease model worked but had edge cases around expiry checking on refresh.

## Decision

We adopt **Variant B**: lazy position computation.

### Position tracking

The aggregate stores `last_known_position_ms` and `last_known_at` (timestamp). The current position is computed lazily on read:

```
current_position = last_known_position_ms + (now - last_known_at)
```

Writes to these fields occur only on **pause**, **seek**, **track-change**, and **stop** events -- not during continuous playback. This eliminates high-frequency position updates.

### Lease model

The lease mechanism from v1 is preserved with fixes:

- Expiry is checked on every refresh call, rejecting refreshes on already-expired leases.
- Lease duration and grace period remain configurable.

### Play history

`play_history` is an append-only side effect, not part of the aggregate's own state. History entries are written in the same database transaction as the aggregate state change, using the transactional outbox pattern. This guarantees consistency without coupling the aggregate to history concerns.

### Derived fields

`play_count` and `last_played` are no longer stored on the aggregate. They are derived from the `play_history` projection, eliminating redundant state.

### Rating

The rating field is dropped from the PlaybackSession aggregate. Rating, if reintroduced, belongs in the `preferences` context.

### Device session

`DeviceSession` is a runtime projection held in Redis (or in-memory), not a persisted aggregate. It tracks which device currently holds the lease and connection metadata. It is reconstructed from events on service restart.

## Consequences

- Write volume drops significantly: position updates happen only on state transitions, not continuously.
- Read-path must perform a small computation (`last_known_position_ms + elapsed`), which is trivial.
- Play history is guaranteed consistent with playback state changes via the outbox, enabling reliable analytics.
- Removing `play_count` / `last_played` from the aggregate eliminates dual-write inconsistencies; consumers read from the history projection instead.
- DeviceSession as a runtime projection avoids persisting ephemeral connection state, simplifying the aggregate and its schema.
- Lease expiry fix closes the v1 edge case where an expired lease could be refreshed.
