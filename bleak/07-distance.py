"""Display only the distance (m) reported by the rower, to compare it with the console.

Keeps the connection alive like 02-connection.py and decodes the distance the
same way as 03-telemetry.py (bytes 12-13 of the 50-byte message). On a terminal
the value is shown on a single line that is updated in place. While the rower
is paused it does not report the distance (it sends 0), so the last value is
kept and marked "paused".

Usage: poetry run python 07-distance.py
"""

import argparse
import asyncio

from bleak.exc import BleakError

from rower import (
    STATE_BYTE,
    MessageAssembler,
    add_connection_arguments,
    keep_alive,
    timestamp,
    u16,
)

STATE_PAUSED = 3
DISTANCE_OFFSET = 12


class DistanceDisplay:
    def __init__(self, in_place):
        self.in_place = in_place
        self.assembler = MessageAssembler()
        self.distance = None
        self.paused = False

    def feed(self, frame):
        message = self.assembler.feed(frame)
        if message is None or not message.checksum_ok:
            return
        body = message.body
        # The 50-byte telemetry message (`01 04 02 2e ...`), as in 03-telemetry.py.
        if body[:4] != b"\x01\x04\x02\x2e" or len(body) != 50 or body[10:18] == b"\xff" * 8:
            return
        paused = body[STATE_BYTE] == STATE_PAUSED
        distance = self.distance if paused and self.distance is not None else u16(body, DISTANCE_OFFSET)
        if distance != self.distance or paused != self.paused:
            self.distance, self.paused = distance, paused
            self.show()

    def show(self):
        suffix = " (paused)" if self.paused else ""
        if self.in_place:
            print(f"\rDistance: {self.distance:5d} m{suffix:<10}", end="", flush=True)
        else:
            print(f"[{timestamp()}] Distance: {self.distance} m{suffix}", flush=True)


if __name__ == "__main__":
    import sys

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_connection_arguments(parser)
    args = parser.parse_args()
    display = DistanceDisplay(sys.stdout.isatty())
    try:
        raise SystemExit(
            asyncio.run(
                keep_alive(
                    args.address,
                    args.interval,
                    args.duration,
                    on_notification=display.feed,
                    log_notifications=False,
                    log_writes=False,
                )
            )
        )
    except (KeyboardInterrupt, BleakError) as error:
        print(f"\n[{timestamp()}] Stopped: {error!r}")
