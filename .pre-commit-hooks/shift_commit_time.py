#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import random
import subprocess
import sys
from datetime import datetime
from typing import Optional, Sequence

AMEND_GUARD = "SHIFT_COMMIT_TIME_AMENDING"


def get_author_date() -> datetime:
    result = subprocess.run(
        ["git", "log", "-1", "--format=%aI"],
        capture_output=True,
        text=True,
        check=True,
    )
    return datetime.fromisoformat(result.stdout.strip())


def get_commit_hash() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def is_work_hours(dt: datetime, start: int, end: int) -> bool:
    if dt.weekday() >= 5:
        return False
    return start <= dt.hour < end


def shifted_time(dt: datetime, work_start: int, work_end: int, seed: str) -> datetime:
    rng = random.Random(seed)
    midpoint = (work_start + work_end) / 2

    if dt.hour + dt.minute / 60 < midpoint:
        target_hour = rng.randint(max(0, work_start - 3), work_start - 1)
    else:
        target_hour = rng.randint(work_end, min(23, work_end + 2))

    return dt.replace(
        hour=target_hour,
        minute=rng.randint(0, 59),
        second=rng.randint(0, 59),
    )


def amend_with_date(new_date: datetime) -> bool:
    date_str = new_date.isoformat()
    env = os.environ.copy()
    env["GIT_COMMITTER_DATE"] = date_str
    env[AMEND_GUARD] = "1"

    result = subprocess.run(
        ["git", "commit", "--amend", "--no-edit", "--no-verify", "--date", date_str],
        env=env,
        capture_output=True,
        text=True,
    )
    return result.returncode == 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    if os.environ.get(AMEND_GUARD):
        return 0

    parser = argparse.ArgumentParser()
    parser.add_argument("--work-start", type=int, default=8)
    parser.add_argument("--work-end", type=int, default=21)
    args = parser.parse_args(argv)

    try:
        commit_date = get_author_date()
    except (subprocess.CalledProcessError, ValueError):
        return 0

    if not is_work_hours(commit_date, args.work_start, args.work_end):
        return 0

    commit_hash = get_commit_hash()
    new_date = shifted_time(commit_date, args.work_start, args.work_end, commit_hash)

    if amend_with_date(new_date):
        print(
            f"Commit time shifted: "
            f"{commit_date.strftime('%a %H:%M')} -> {new_date.strftime('%H:%M')}",
            file=sys.stderr,
        )

    return 0


if __name__ == "__main__":
    sys.exit(main())
