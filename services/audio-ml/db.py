import logging
import os
import time

import psycopg2

log = logging.getLogger("db")

_CONNECT_ATTEMPTS = 6
_RETRY_SLEEP_SECONDS = 5


def connect():
    # A freshly-scheduled pod's Cilium identity can lag the destination's policy
    # enforcement for a few seconds, so the first connect to the pooler is refused
    # (RST) before the identity propagates. Retry with backoff to ride that window.
    last_err = None
    for attempt in range(1, _CONNECT_ATTEMPTS + 1):
        try:
            return psycopg2.connect(
                host=os.getenv("POSTGRES_HOST") or os.getenv("DB_HOST", "localhost"),
                port=os.getenv("POSTGRES_PORT") or os.getenv("DB_PORT", "5432"),
                dbname=os.getenv("POSTGRES_DB") or os.getenv("DB_NAME", "yaytsa_production"),
                user=os.getenv("POSTGRES_USER") or os.getenv("DB_USERNAME", "yaytsa_production"),
                password=os.getenv("POSTGRES_PASSWORD") or os.getenv("DB_PASSWORD", ""),
            )
        except psycopg2.OperationalError as exc:
            last_err = exc
            log.warning("DB connect attempt %d/%d failed: %s", attempt, _CONNECT_ATTEMPTS, exc)
            time.sleep(_RETRY_SLEEP_SECONDS)
    raise last_err or RuntimeError("DB connection failed after retries")


def vector(values):
    if values is None or len(values) == 0:
        return None
    return "[" + ",".join(str(float(v)) for v in values) + "]"
