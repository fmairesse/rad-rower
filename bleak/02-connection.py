"""Keep a BLE connection open with the ProForm Sport RL rower, with few writes.

Nothing asks for confirmation and no resistance command is ever sent. Every
notification is logged with its local time and raw bytes.

Usage: poetry run python 02-connection.py [--interval 0.6] [--duration 0]
"""

import argparse
import asyncio

from bleak.exc import BleakError

from rower import add_connection_arguments, keep_alive, timestamp

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_connection_arguments(parser)
    args = parser.parse_args()
    try:
        raise SystemExit(asyncio.run(keep_alive(args.address, args.interval, args.duration)))
    except (KeyboardInterrupt, BleakError) as error:
        print(f"[{timestamp()}] Stopped: {error!r}")
