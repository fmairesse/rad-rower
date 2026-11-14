"""Read rower metrics while keeping the connection alive, decoding like QZ.

Notification frames are reassembled into messages (header `fe 02 <length>
<frames>`, data frames `NN 12 ...`, last frame `ff <n> ...`) and decoded with
the offsets used by QZ (qdomyos-zwift, proformrower.cpp). Validated on our
rower: watts, distance, resistance (50-byte message), and cadence, stroke count,
pace and speed (`ff 11` frame). Raw bytes are printed next to the values.

Usage: poetry run python 03-telemetry.py [--raw] [--all] [--duration 60]
"""

import argparse
import asyncio
from dataclasses import dataclass

from bleak.exc import BleakError

from rower import add_connection_arguments, keep_alive, timestamp

# Resistance level from the raw byte (QZ, GetResistanceFromPacket).
RESISTANCE_LEVELS = {
    0x01: 1, 0x03: 2, 0x04: 3, 0x06: 4, 0x07: 5, 0x08: 5, 0x09: 6, 0x0B: 7,
    0x0C: 8, 0x0D: 8, 0x0E: 9, 0x10: 10, 0x11: 11, 0x13: 12, 0x14: 13,
    0x15: 13, 0x16: 14, 0x18: 15, 0x19: 16, 0x1A: 16, 0x1B: 17, 0x1D: 18,
    0x1E: 19, 0x20: 20, 0x22: 21, 0x23: 22, 0x25: 23, 0x26: 24, 0x27: 24,
}
FRAME_SIZE = 20


@dataclass
class Message:
    body: bytes  # data bytes of all frames, without frame headers
    checksum_ok: bool


class MessageAssembler:
    """Rebuild messages from 20-byte frames (same rules as QZ)."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.body = bytearray()
        self.length = 0
        self.frames = 0
        self.received = 0

    def feed(self, frame):
        if len(frame) != FRAME_SIZE:
            self.reset()
            return None
        if frame[0] == 0xFE and frame[1] == 0x02:
            # Bytes 4.. of a header frame repeat stale data and are ignored.
            self.reset()
            self.length, self.frames = frame[2], frame[3]
            if self.frames >= 2:
                self.received = 1
            return None
        if self.received == 0:
            return None

        if frame[0] == self.received - 1 and frame[1] == 0x12 and self.received < self.frames - 1:
            if len(self.body) + 18 > self.length:
                self.reset()
                return None
            self.body += frame[2:20]
            self.received += 1
            return None
        if frame[0] == 0xFF and self.received == self.frames - 1:
            count = frame[1]
            if not 0 < count <= 18 or count != self.length - len(self.body):
                self.reset()
                return None
            self.body += frame[2 : 2 + count]
            body = bytes(self.body)
            self.reset()
            checksum_ok = len(body) >= 2 and sum(body[4:-1]) & 0xFF == body[-1]
            return Message(body, checksum_ok)
        self.reset()
        return None


def u16(data, offset):
    return data[offset] | data[offset + 1] << 8


def pace_fields(pace):
    """`pace` is seconds per 500 m; speed = 1800 / pace km/h (QZ formula)."""
    if not pace:
        return {"pace_500m": "-", "speed_kmh": 0.0}
    return {"pace_500m": f"{pace // 60}:{pace % 60:02d}", "speed_kmh": round(1800 / pace, 2)}


def decode_message(body):
    """Return {name: value} for known telemetry messages, else None.

    Offsets are in `body`; body[0] is byte 2 of the first data frame.
    """
    if body[:3] == b"\x01\x04\x02" and body[3] in (0x2E, 0x30, 0x31) and len(body) == body[3] + 4:
        if body[10:18] == b"\xff" * 8:
            return {"state": "no data (0xff)"}
        resistance = "unset" if body[9] == 0 else RESISTANCE_LEVELS.get(body[9], f"?0x{body[9]:02x}")
        return {
            "watts": u16(body, 10),
            "resistance": resistance,
            "distance_m": u16(body, 12),
        }
    if len(body) == 33 and body.startswith(bytes.fromhex("0104021d141d0202")):
        return pace_fields(u16(body, 27))
    return None


def decode_ff11_frame(frame):
    """QZ rule for single `ff 11 00 00 00 00 00 00 00 00 ...` frames.

    Cadence (strokes per minute) and pace are the QZ offsets. The stroke count
    (bytes 10-11) is not used by QZ; it was found on our rower. All validated.
    """
    if len(frame) != FRAME_SIZE or frame[:2] != b"\xff\x11" or any(frame[2:10]):
        return None
    return {
        "cadence_spm": frame[12],
        "strokes": u16(frame, 10),
        **pace_fields(u16(frame, 13)),
    }


class Telemetry:
    def __init__(self, show_all):
        self.show_all = show_all
        self.assembler = MessageAssembler()

    def feed(self, frame):
        extra = decode_ff11_frame(frame)
        if extra is not None:
            print(f"[{timestamp()}] ff11 {format_values(extra)} | {frame.hex(' ')}", flush=True)

        message = self.assembler.feed(frame)
        if message is None:
            return
        values = decode_message(message.body)
        if values is None and not self.show_all:
            return
        checksum = "ok" if message.checksum_ok else "BAD"
        decoded = format_values(values) if values else "(unknown)"
        print(
            f"[{timestamp()}] {len(message.body)}B checksum={checksum} {decoded} | {message.body.hex(' ')}",
            flush=True,
        )


def format_values(values):
    return " ".join(f"{name}={value}" for name, value in values.items())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_connection_arguments(parser)
    parser.add_argument("--raw", action="store_true", help="also log every raw frame and every write")
    parser.add_argument("--all", action="store_true", help="also print messages that are not telemetry")
    args = parser.parse_args()
    telemetry = Telemetry(args.all)
    try:
        raise SystemExit(
            asyncio.run(
                keep_alive(
                    args.address,
                    args.interval,
                    args.duration,
                    on_notification=telemetry.feed,
                    log_notifications=args.raw,
                    log_writes=args.raw,
                )
            )
        )
    except (KeyboardInterrupt, BleakError) as error:
        print(f"[{timestamp()}] Stopped: {error!r}")
