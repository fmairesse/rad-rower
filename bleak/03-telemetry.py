"""Read rower metrics while keeping the connection alive, decoding like QZ.

Notification frames are reassembled into messages (header `fe 02 <length>
<frames>`, data frames `NN 12 ...`, last frame `ff <n> ...`) and decoded with
the offsets used by QZ (qdomyos-zwift, proformrower.cpp). Validated on our
rower: watts, distance, resistance (50-byte message), and cadence, stroke count,
pace and speed (`ff 11` frame). Also decoded from console readings on 2026-11-14
(docs/protocole-ble.md): elapsed time, calories, calories per hour (computed
from watts), average watts, paused time, average pace. Raw bytes are printed
next to the values.

Usage: poetry run python 03-telemetry.py [--raw] [--all] [--duration 60]
"""

import argparse
import asyncio

from bleak.exc import BleakError

from rower import FRAME_SIZE, MessageAssembler, add_connection_arguments, keep_alive, timestamp, u16

# Resistance level from the raw byte (QZ, GetResistanceFromPacket).
RESISTANCE_LEVELS = {
    0x01: 1, 0x03: 2, 0x04: 3, 0x06: 4, 0x07: 5, 0x08: 5, 0x09: 6, 0x0B: 7,
    0x0C: 8, 0x0D: 8, 0x0E: 9, 0x10: 10, 0x11: 11, 0x13: 12, 0x14: 13,
    0x15: 13, 0x16: 14, 0x18: 15, 0x19: 16, 0x1A: 16, 0x1B: 17, 0x1D: 18,
    0x1E: 19, 0x20: 20, 0x22: 21, 0x23: 22, 0x25: 23, 0x26: 24, 0x27: 24,
}


def pace_fields(pace):
    """`pace` is seconds per 500 m; speed = 1800 / pace km/h (QZ formula)."""
    if not pace:
        return {"pace_500m": "-", "speed_kmh": 0.0}
    return {"pace_500m": f"{pace // 60}:{pace % 60:02d}", "speed_kmh": round(1800 / pace, 2)}


# Calories are a cumulative counter in 1/100 000 kcal; the console's cals/hr
# follows watts linearly (fit on 6 readings, residuals under 0.5).
CALORIES_SCALE = 100_000
CALS_HOUR_SLOPE = 3.2438
CALS_HOUR_OFFSET = 174.66


def u32(data, offset):
    return u16(data, offset) | u16(data, offset + 2) << 16


def decode_message(body):
    """Return {name: value} for known telemetry messages, else None.

    Offsets are in `body`; body[0] is byte 2 of the first data frame.
    """
    if body[:3] == b"\x01\x04\x02" and body[3] in (0x2E, 0x30, 0x31) and len(body) == body[3] + 4:
        if body[10:18] == b"\xff" * 8:
            return {"state": "no data (0xff)"}
        resistance = "unset" if body[9] == 0 else RESISTANCE_LEVELS.get(body[9], f"?0x{body[9]:02x}")
        values = {
            "watts": u16(body, 10),
            "resistance": resistance,
            "distance_m": u16(body, 12),
        }
        if body[3] == 0x2E:
            values["time_s"] = body[21]
            values["calories"] = round(u32(body, 34) / CALORIES_SCALE, 2)
            values["avg_watts"] = body[40]
            values["paused_s"] = body[45]
            if values["watts"]:
                values["cals_per_hour"] = round(CALS_HOUR_SLOPE * values["watts"] + CALS_HOUR_OFFSET)
        return values
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
        "avg_pace_500m": pace_fields(u16(frame, 15))["pace_500m"],
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
