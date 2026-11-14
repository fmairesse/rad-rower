"""Set ProForm Sport RL resistance using the QZ command table.

Prints the exact command without connecting by default. With --send, requires
the rower to be idle and confirms the requested level from telemetry.

Usage: poetry run python 08-set-resistance.py --level 12 [--send]
"""

import argparse
import asyncio

from bleak.exc import BleakError

from rower import (
    H0D,
    MessageAssembler,
    add_connection_arguments,
    keep_alive,
    message,
    timestamp,
)

RESISTANCE_WORDS = (
    0x0176, 0x0317, 0x04B8, 0x0658, 0x07F9, 0x099A, 0x0B3A, 0x0CDB,
    0x0E7C, 0x101C, 0x11BD, 0x135E, 0x14FE, 0x169F, 0x1840, 0x19E0,
    0x1B81, 0x1D22, 0x1EC2, 0x2063, 0x2204, 0x23A4, 0x2545, 0x26E6,
)
IDLE = 1
STATE_BYTE = 25
TELEMETRY_TIMEOUT = 20.0
CONFIRM_TIMEOUT = 8.0


def resistance_command(level):
    value = RESISTANCE_WORDS[level - 1]
    payload = bytearray.fromhex("ff 0d 02 04 02 09 14 09 02 01 04")
    payload.extend(value.to_bytes(2, "little"))
    payload.append(0)
    payload.append((sum(payload[9:]) + 0x1F) & 0xFF)
    payload.extend(bytes(20 - len(payload)))
    return message(H0D, payload.hex())


def log(text):
    print(f"[{timestamp()}] {text}", flush=True)


class ResistanceTest:
    def __init__(self, level):
        self.level = level
        self.frames = resistance_command(level)
        self.expected_raw = RESISTANCE_WORDS[level - 1] >> 8
        self.assembler = MessageAssembler()
        self.state = None
        self.raw_resistance = None
        self.telemetry_count = 0
        self.exit_code = 0

    def feed(self, frame):
        assembled = self.assembler.feed(frame)
        if assembled is None or not assembled.checksum_ok:
            return
        body = assembled.body
        if (
            body[:4] != b"\x01\x04\x02\x2e"
            or len(body) != 50
            or body[10:18] == b"\xff" * 8
        ):
            return
        self.state = body[STATE_BYTE]
        self.raw_resistance = body[9]
        self.telemetry_count += 1
        log(
            f"Telemetry: state={self.state}, "
            f"resistance raw=0x{self.raw_resistance:02x}"
        )

    def on_ready(self, rower):
        asyncio.create_task(self.run(rower))

    async def wait_for(self, predicate, timeout):
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while loop.time() < deadline:
            if predicate():
                return True
            await asyncio.sleep(0.1)
        return False

    async def run(self, rower):
        try:
            has_telemetry = await self.wait_for(
                lambda: self.state is not None, TELEMETRY_TIMEOUT
            )
            if not has_telemetry:
                log("No valid rower telemetry received; nothing sent.")
                self.exit_code = 1
                return
            if self.state != IDLE:
                log(f"Rower state is {self.state}, not idle; nothing sent.")
                self.exit_code = 1
                return

            previous_count = self.telemetry_count
            log(f"Sending resistance level {self.level}.")
            rower.queue(
                (
                    ("unlock resistance", (self.frames[0],)),
                    (f"resistance {self.level}", (self.frames[1],)),
                )
            )
            confirmed = await self.wait_for(
                lambda: self.telemetry_count > previous_count
                and self.raw_resistance == self.expected_raw,
                CONFIRM_TIMEOUT,
            )
            if confirmed:
                log(f"Confirmed resistance level {self.level} from telemetry.")
            else:
                log(
                    f"Not confirmed: expected raw 0x{self.expected_raw:02x}, "
                    f"last raw value was {self.raw_resistance!r}."
                )
                self.exit_code = 1
        finally:
            rower.stopped.set()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_connection_arguments(parser)
    parser.add_argument(
        "--level",
        type=int,
        required=True,
        choices=range(1, 25),
        help="target resistance (1-24)",
    )
    parser.add_argument(
        "--send",
        action="store_true",
        help="connect and send the command (default: print only)",
    )
    parser.add_argument(
        "--raw", action="store_true", help="also log raw BLE frames and writes"
    )
    args = parser.parse_args()
    test = ResistanceTest(args.level)

    if not args.send:
        log(f"Dry run for resistance level {args.level}:")
        labels = ("unlock resistance", f"resistance {args.level}")
        for label, frame in zip(labels, test.frames):
            log(f"  >> {frame.hex(' ')} // {label}")
        log("Run again with --send to connect and send the command.")
    else:
        try:
            code = asyncio.run(
                keep_alive(
                    args.address,
                    args.interval,
                    args.duration,
                    on_notification=test.feed,
                    log_notifications=args.raw,
                    log_writes=args.raw,
                    on_ready=test.on_ready,
                )
            )
            raise SystemExit(code or test.exit_code)
        except (KeyboardInterrupt, BleakError) as error:
            log(f"Stopped: {error!r}")
