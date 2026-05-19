package dev.yaytsa.shared

fun guardOcc(
    snapshotVersion: AggregateVersion,
    expected: AggregateVersion,
): CommandResult.Failed? =
    if (snapshotVersion != expected) {
        CommandResult.Failed(Failure.Conflict(expected, snapshotVersion))
    } else {
        null
    }
