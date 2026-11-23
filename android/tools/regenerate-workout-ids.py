#!/usr/bin/env python3
"""Give every workout in the Radrower database of a connected phone a new UUID.

Health Connect record IDs are derived from the workout ID ("radrower:<id>:..."),
so a new ID makes the next export create fresh records instead of overwriting
the previous ones. Useful to force Health Sync (or another app) to import an
export again. Delete the old records from Health Connect first, or they will
be duplicated.

The app data is read and written through `adb run-as`, which needs a debuggable
build. The database is backed up locally before any change.

Usage: regenerate-workout-ids.py [--serial SERIAL] [--backup-dir DIR] [--dry-run]
"""
import argparse
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime
from pathlib import Path

PACKAGE = "io.radrower"
DB_NAME = "workouts.db"
DB_FILES = [DB_NAME, f"{DB_NAME}-wal", f"{DB_NAME}-shm"]
DEVICE_TMP = "/data/local/tmp/radrower-workouts.db"


def adb(args, serial, capture=False, timeout=60):
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    result = subprocess.run(cmd, capture_output=capture, timeout=timeout)
    if result.returncode != 0:
        sys.exit(f"Command failed: {' '.join(cmd)}\n{(result.stderr or b'').decode()}")
    return result.stdout


def pull_database(serial, directory):
    for name in DB_FILES:
        # Stream through exec-out; -wal and -shm may legitimately be empty or missing.
        data = subprocess.run(
            ["adb"] + (["-s", serial] if serial else []) + ["exec-out", "run-as", PACKAGE, "cat", f"databases/{name}"],
            capture_output=True, timeout=60,
        ).stdout
        if name == DB_NAME and not data:
            sys.exit(f"Could not read {DB_NAME}: is {PACKAGE} installed as a debug build?")
        if data:
            (directory / name).write_bytes(data)


def regenerate_ids(db_path):
    conn = sqlite3.connect(db_path)
    try:
        # Rewrite the parent and child keys together; the foreign key is checked afterwards.
        conn.execute("PRAGMA foreign_keys=OFF")
        changes = []
        has_exported = any(row[1] == "exported" for row in conn.execute("PRAGMA table_info(workouts)"))
        for (old,) in conn.execute("SELECT id FROM workouts").fetchall():
            new = str(uuid.uuid4())
            conn.execute("UPDATE workouts SET id = ? WHERE id = ?", (new, old))
            if has_exported:
                conn.execute("UPDATE workouts SET exported = 0 WHERE id = ?", (new,))
            conn.execute("UPDATE samples SET workoutId = ? WHERE workoutId = ?", (new, old))
            conn.execute("UPDATE segments SET workoutId = ? WHERE workoutId = ?", (new, old))
            changes.append((old, new))
        conn.commit()
        # Fold the WAL into the main file so a single file can be pushed back.
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        if conn.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or conn.execute("PRAGMA foreign_key_check").fetchall():
            sys.exit("Integrity check failed; nothing was written to the phone.")
        return changes
    finally:
        conn.close()


def push_database(serial, db_path):
    # Pushing to the app directory directly is not allowed; copy through a shared temp path.
    adb(["shell", "am", "force-stop", PACKAGE], serial)
    adb(["push", str(db_path), DEVICE_TMP], serial, capture=True)
    adb(["shell", f"run-as {PACKAGE} rm -f databases/{DB_NAME}-wal databases/{DB_NAME}-shm; "
                  f"run-as {PACKAGE} cp {DEVICE_TMP} databases/{DB_NAME}"], serial)
    adb(["shell", "rm", "-f", DEVICE_TMP], serial)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", help="adb device serial (needed when several devices are connected)")
    parser.add_argument("--backup-dir", type=Path, default=Path("workouts-db-backup"),
                        help="parent directory for the timestamped backup (default: %(default)s)")
    parser.add_argument("--dry-run", action="store_true", help="show the new IDs without changing the phone")
    args = parser.parse_args()

    adb(["shell", "am", "force-stop", PACKAGE], args.serial)
    backup = args.backup_dir / datetime.now().strftime("%Y%m%d-%H%M%S")
    backup.mkdir(parents=True)
    pull_database(args.serial, backup)
    print(f"Backup saved in {backup}")

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        for path in backup.iterdir():
            shutil.copy(path, work / path.name)
        changes = regenerate_ids(work / DB_NAME)
        for old, new in changes:
            print(f"{old} -> {new}")
        if args.dry_run:
            print("Dry run: the phone was not modified.")
            return
        push_database(args.serial, work / DB_NAME)
    print(f"{len(changes)} workout(s) updated. Delete the old records from Health Connect and Strava before exporting again.")


if __name__ == "__main__":
    main()
