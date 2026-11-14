"""Keep a BLE connection open with the ProForm Sport RL rower, with few writes.

The init and poll messages come from the "Sport RL" branch of
qdomyos-zwift (src/devices/proformrower/proformrower.cpp). No resistance
command is ever sent, and nothing asks for confirmation. Every notification is
logged with its local time and raw bytes.

Usage: poetry run python 02-connection.py [--interval 0.6] [--duration 0]
"""

import argparse
import asyncio
from datetime import datetime

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

TARGET_NAME = "I_RW"
WRITE_CHAR_UUID = "00001534-1412-efde-1523-785feabcd123"
NOTIFY_CHAR_UUID = "00001535-1412-efde-1523-785feabcd123"

# A message is a header frame `fe 02 <length> <frames>`, then data frames
# (`00 12 ...`, `01 12 ...`) and a last frame `ff <length> ...`. The rower
# expects all frames of a message (see docs/protocole-ble.md, "Message framing").
# Frames are sent in order, each one acknowledged by the rower (write response).
def message(*frames_hex):
    return tuple(bytes.fromhex(frame) for frame in frames_hex)


H08 = "fe020802"
H0A = "fe020a02"
H17 = "fe021703"
H19 = "fe021903"

SETUP = (
    ("08 #1", message(H08, "ff08020402040204818700000000000000000000")),
    ("08 #2", message(H08, "ff08020402041404809800000000000000000000")),
    ("08 #3", message(H08, "ff0802040204140488a000000000000000000000")),
    ("0a #1", message(H0A, "ff0a0204020602068200008a0000000000000000")),
    ("0a #2", message(H0A, "ff0a0204020602068400008c0000000000000000")),
    ("08 #4", message(H08, "ff08020402040204959b00000000000000000000")),
    (
        "2c",
        message(
            "fe022c04",
            "0012020402281428900701f2f4f4f2fe08001e2a",
            "01123c4c5a669088a6c2e404224e98b0ce1a2c7c",
            "ff088ad620980200000000000000000000000000",
        ),
    ),
    (
        "17 #1",
        message(
            H17,
            "0012020402131413020c00000000000000000000",
            "ff0500800100b600000000000000000000000000",
        ),
    ),
    (
        "19 #1",
        message(
            H19,
            "0012020402151415020e00000000000000000000",
            "ff070000001001004a0000000000000000000000",
        ),
    ),
    (
        "19 #2",
        message(
            H19,
            "001202040215141502000f001000c01c4c0000e0",
            "ff070000001000086a0000000000000000000000",
        ),
    ),
)

# Messages sent alternately to keep the connection alive.
POLL = (
    (
        "poll 17",
        message(
            H17,
            "001202040213141302000d1c9e31000040400080",
            "ff0500000081a200000000000000000000000000",
        ),
    ),
    (
        "poll 19",
        message(
            H19,
            "001202040215141502000f800040000000000000",
            "ff0700000000e011eb0000000000000000000000",
        ),
    ),
)

# After a write, wait until the rower has been silent this long (or for this max).
QUIET_TIME = 0.15
MAX_WAIT = 1.5


def timestamp():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


class Rower:
    def __init__(self, client):
        self.client = client
        self.last_notification = 0.0

    def on_notification(self, sender, data):
        self.last_notification = asyncio.get_running_loop().time()
        print(f"[{timestamp()}] << {bytes(data).hex(' ')}", flush=True)

    async def send(self, label, frames, wait=True):
        for frame in frames:
            print(f"[{timestamp()}] >> {frame.hex(' ')} // {label}", flush=True)
            await self.client.write_gatt_char(WRITE_CHAR_UUID, frame, response=True)
        if wait:
            await self.wait_quiet()

    async def wait_quiet(self):
        loop = asyncio.get_running_loop()
        deadline = loop.time() + MAX_WAIT
        await asyncio.sleep(QUIET_TIME)
        while loop.time() < deadline and loop.time() - self.last_notification < QUIET_TIME:
            await asyncio.sleep(0.05)

    async def run(self, interval, duration):
        loop = asyncio.get_running_loop()
        for label, frames in SETUP:
            await self.send(label, frames)

        started = loop.time()
        next_at = started
        index = 0
        while duration <= 0 or loop.time() - started < duration:
            label, frames = POLL[index % len(POLL)]
            await self.send(label, frames, wait=False)
            index += 1
            next_at += interval
            await asyncio.sleep(max(0.0, next_at - loop.time()))


async def main(interval, duration, address):
    if address is None:
        print(f"Searching for {TARGET_NAME}...")
        device = await BleakScanner.find_device_by_name(TARGET_NAME, timeout=10.0)
        if device is None:
            print("Rower not found. Check it is on and not connected to iFit.")
            return 1
        address = device.address
    print(f"Connecting to {address}...")

    disconnected = asyncio.Event()
    async with BleakClient(address, disconnected_callback=lambda _: disconnected.set()) as client:
        print("Connected.")
        rower = Rower(client)
        await client.start_notify(NOTIFY_CHAR_UUID, rower.on_notification)

        task = asyncio.create_task(rower.run(interval, duration))
        gone = asyncio.create_task(disconnected.wait())
        try:
            await asyncio.wait({task, gone}, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for pending in (task, gone):
                pending.cancel()
        if task.done() and not task.cancelled() and task.exception() is None:
            print("Duration elapsed.")
        elif disconnected.is_set():
            print(f"[{timestamp()}] Rower disconnected.")
        elif task.done() and not task.cancelled():
            print(f"[{timestamp()}] Stopped: {task.exception()!r}")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--interval", type=float, default=0.6, help="seconds between poll messages")
    parser.add_argument("--duration", type=float, default=0, help="seconds of polling (0 = until Ctrl+C)")
    parser.add_argument("--address", help="skip the scan and connect to this address")
    args = parser.parse_args()
    try:
        raise SystemExit(asyncio.run(main(args.interval, args.duration, args.address)))
    except (KeyboardInterrupt, BleakError) as error:
        print(f"Stopped: {error!r}")
