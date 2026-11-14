"""Shared BLE code for the ProForm Sport RL rower: connection, init and keep-alive.

The init and poll messages come from the "Sport RL" branch of
qdomyos-zwift ("QZ", src/devices/proformrower/proformrower.cpp). No resistance
command is ever sent. See docs/protocole-ble.md.

`keep_alive()` connects, runs the init sequence, then polls until the duration
elapses, the rower disconnects or the task is cancelled. Callers receive each
notification (raw bytes, one 20-byte frame at a time) through `on_notification`.
"""

import asyncio
from collections.abc import Callable
from datetime import datetime

from bleak import BleakClient, BleakScanner

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
DEFAULT_INTERVAL = 0.6


def timestamp():
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


class Rower:
    def __init__(self, client, on_notification=None, log_notifications=True):
        self.client = client
        self.on_notification_callback = on_notification
        self.log_notifications = log_notifications
        self.last_notification = 0.0

    def on_notification(self, sender, data):
        data = bytes(data)
        self.last_notification = asyncio.get_running_loop().time()
        if self.log_notifications:
            print(f"[{timestamp()}] << {data.hex(' ')}", flush=True)
        if self.on_notification_callback is not None:
            self.on_notification_callback(data)

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


async def find_address():
    print(f"Searching for {TARGET_NAME}...")
    device = await BleakScanner.find_device_by_name(TARGET_NAME, timeout=10.0)
    if device is None:
        print("Rower not found. Check it is on and not connected to iFit.")
        return None
    return device.address


async def keep_alive(
    address=None,
    interval=DEFAULT_INTERVAL,
    duration=0,
    on_notification: Callable[[bytes], None] | None = None,
    log_notifications=True,
):
    """Connect and keep the link alive. Return a process exit code."""
    if address is None:
        address = await find_address()
        if address is None:
            return 1
    print(f"Connecting to {address}...")

    disconnected = asyncio.Event()
    async with BleakClient(address, disconnected_callback=lambda _: disconnected.set()) as client:
        print("Connected.")
        rower = Rower(client, on_notification, log_notifications)
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


def add_connection_arguments(parser):
    parser.add_argument(
        "--interval", type=float, default=DEFAULT_INTERVAL, help="seconds between poll messages"
    )
    parser.add_argument("--duration", type=float, default=0, help="seconds of polling (0 = until Ctrl+C)")
    parser.add_argument("--address", help="skip the scan and connect to this address")
