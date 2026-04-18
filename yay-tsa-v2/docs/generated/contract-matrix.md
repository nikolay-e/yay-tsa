# Contract Verification Matrix

Auto-generated from source code. Do not edit manually.

| Context     | Aggregate                | OCC | Idempotency | Outbox | Protocol Check | Deps Pattern | Lease |
| ----------- | ------------------------ | --- | ----------- | ------ | -------------- | ------------ | ----- |
| auth        | UserAggregate            | ✓   | ✓           | ✓      | ✓              | ✗            | ✗     |
| playback    | PlaybackSessionAggregate | ✓   | ✓           | ✓      | ✓              | ✓            | ✓     |
| playlists   | PlaylistAggregate        | ✓   | ✓           | ✓      | ✓              | ✓            | ✗     |
| preferences | UserPreferencesAggregate | ✓   | ✓           | ✓      | ✓              | ✓            | ✗     |
| adaptive    | AdaptiveSessionAggregate | ✓   | ✓           | ✓      | ✓              | ✓            | ✗     |
| library     | _(read-only)_            | n/a | n/a         | n/a    | n/a            | n/a          | n/a   |
| ml          | _(read-only)_            | n/a | n/a         | n/a    | n/a            | n/a          | n/a   |
| karaoke     | _(read-only)_            | n/a | n/a         | n/a    | n/a            | n/a          | n/a   |

## Legend

- **OCC**: Optimistic Concurrency Control via `expectedVersion`
- **Idempotency**: Uses `IdempotencyStore` for replay protection
- **Outbox**: Enqueues `DomainNotification` in same transaction
- **Protocol Check**: Validates command against `ProtocolCapabilities`
- **Deps Pattern**: Cross-context data loaded before handler call
- **Lease**: Single-writer lease enforcement
