"""Row a session and log every telemetry message, to decode the unknown fields.

Starts a session (like 05-start-pause-stop.py), lets you row for --row seconds,
pauses it, then keeps polling for --hold seconds so that you can read the
rower's console while the values are frozen. Finally the session is stopped
(pause, end, idle), unless --no-stop is given.

Every message (reassembled like 03-telemetry.py) and every `ff 11` frame is
printed in hex with its local time, but only when it differs from the previous
one of the same kind. Use `| tee ../bugreport/session-<date>.log` to keep it.

The console shows one value at a time (strokes, cals, cals/hr, strokes/minute,
resistance, 500 m split, time, distance, watts, speed). While paused, press the
console button to cycle through them and write down each value, in order, with
the clock time you read it. Optional: read cals/hr (or any other value) while
rowing and note the time too.

With --mark N, a "MARK" line is printed and a sound is played (paplay) every N seconds
while rowing: leave the console on one value (for example cals) and write down
what it shows at each mark.

Usage: poetry run python 06-read-session.py [--row 90] [--hold 180] [--mark 15] [--no-stop]
"""

import argparse
import asyncio

from bleak.exc import BleakError

from rower import (
    END,
    IDLE,
    PAUSE,
    START,
    STATE_BYTE,
    STATE_NAMES,
    MessageAssembler,
    add_connection_arguments,
    keep_alive,
    timestamp,
)

STATE_IDLE, STATE_RUNNING, STATE_PAUSED, STATE_FINISHED = 1, 2, 3, 4
STATE_TIMEOUT = 15.0
CONFIRM_TIMEOUT = 5.0


BEEP_COMMAND = ("paplay", "/usr/share/sounds/sound-icons/prompt")


def log(text):
    print(f"[{timestamp()}] {text}", flush=True)


async def beep():
    # Played in the background; a missing paplay must not stop the session.
    try:
        process = await asyncio.create_subprocess_exec(*BEEP_COMMAND, stderr=asyncio.subprocess.DEVNULL)
        asyncio.create_task(process.wait())
    except OSError as error:
        log(f"Beep failed: {error!r}")


class Recorder:
    def __init__(self, row, hold, mark, stop):
        self.row = row
        self.hold = hold
        self.mark = mark
        self.stop = stop
        self.assembler = MessageAssembler()
        self.last = {}
        self.state = None
        self.exit_code = 0
        self.task = None

    def feed(self, frame):
        if len(frame) == 20 and frame[:2] == b"\xff\x11" and not any(frame[2:10]):
            self.print_if_changed("ff11", frame)
        message = self.assembler.feed(frame)
        if message is None:
            return
        body = message.body
        if not message.checksum_ok:
            log(f"BAD checksum {len(body)}B | {body.hex(' ')}")
            return
        if body[:4] == b"\x01\x04\x02\x2e" and len(body) == 50 and body[10:18] != b"\xff" * 8:
            state = body[STATE_BYTE]
            if state != self.state:
                log(f"State: {STATE_NAMES.get(state, '?')} ({state})")
                self.state = state
        self.print_if_changed(f"{len(body)}B {body[:4].hex()}", body)

    def print_if_changed(self, kind, data):
        if self.last.get(kind) != data:
            self.last[kind] = data
            log(f"{kind} | {data.hex(' ')}")

    def on_ready(self, rower):
        self.task = asyncio.create_task(self.run(rower))

    async def wait_state(self, expected, timeout):
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while loop.time() < deadline:
            if self.state is not None and (expected is None or self.state == expected):
                return True
            await asyncio.sleep(0.1)
        return False

    async def step(self, rower, label, messages, expected):
        log(f"Sending {label}.")
        rower.queue(messages)
        if await self.wait_state(expected, CONFIRM_TIMEOUT):
            log(f"{label}: confirmed ({STATE_NAMES[expected]}).")
            return True
        log(f"{label}: state did not become {STATE_NAMES[expected]}.")
        return False

    async def run(self, rower):
        try:
            await self.play(rower)
        finally:
            rower.stopped.set()

    async def row_phase(self):
        loop = asyncio.get_running_loop()
        started = loop.time()
        number = 0
        while (remaining := self.row - (loop.time() - started)) > 0:
            if self.mark <= 0:
                await asyncio.sleep(remaining)
                return
            number += 1
            await asyncio.sleep(min(self.mark, remaining))
            if loop.time() - started < self.row - 0.5:
                log(f"MARK {number}: read the console now.")
                await beep()

    async def play(self, rower):
        if not await self.wait_state(None, STATE_TIMEOUT):
            log("No rower state received. Nothing sent.")
            self.exit_code = 1
            return
        if self.state != STATE_IDLE:
            log("Warning: the rower is not idle, the timer will not start from 0.")
        if not await self.step(rower, "start", START, STATE_RUNNING):
            self.exit_code = 1
            return
        log(f"ROW NOW for {self.row:.0f} s.")
        await self.row_phase()
        if not await self.step(rower, "pause", PAUSE, STATE_PAUSED):
            self.exit_code = 1
            return
        log(f"PAUSED: read the console values now ({self.hold:.0f} s).")
        await asyncio.sleep(self.hold)
        if not self.stop:
            log("Left paused (--no-stop).")
            return
        await self.step(rower, "end", END, STATE_FINISHED)
        if not await self.step(rower, "idle", IDLE, STATE_IDLE):
            self.exit_code = 1
        log("Session finished.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_connection_arguments(parser)
    parser.add_argument("--row", type=float, default=90, help="seconds of rowing before the pause")
    parser.add_argument("--hold", type=float, default=180, help="seconds kept paused to read the console")
    parser.add_argument("--mark", type=float, default=0, help="print a MARK line every N seconds while rowing")
    parser.add_argument("--no-stop", action="store_true", help="leave the session paused at the end")
    args = parser.parse_args()
    recorder = Recorder(args.row, args.hold, args.mark, not args.no_stop)
    try:
        code = asyncio.run(
            keep_alive(
                args.address,
                args.interval,
                args.duration,
                on_notification=recorder.feed,
                log_notifications=False,
                log_writes=False,
                on_ready=recorder.on_ready,
            )
        )
        raise SystemExit(code or recorder.exit_code)
    except (KeyboardInterrupt, BleakError) as error:
        log(f"Stopped: {error!r}")
