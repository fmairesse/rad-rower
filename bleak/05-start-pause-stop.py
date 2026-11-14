"""Run a short training session on the ProForm Sport RL rower, like iFit does.

Connects like 02-connection.py, waits until the rower reports its state, then
replays the iFit state commands, 10 s apart:

1. start (a `17` message, then `fe 02 0d 02` / `ff 0d ... 02 00 33`);
2. 10 s later, pause;
3. 10 s later, resume (the start sequence again; the timer is not reset);
4. 10 s later, stop: pause, end, then back to idle.

Each step waits for the rower to report the expected state (byte 25 of the
50-byte message: 1 idle, 2 running, 3 paused). If a required step is not
confirmed within 5 s, the script stops there without sending anything else.
The start is sent right after connecting, whatever the rower state: from
"paused" it resumes the session instead of restarting it. No resistance command
is ever sent. If the script is interrupted, the session is left as it is; stop
it from the rower.

Usage: poetry run python 05-start-pause-stop.py [--raw]
"""

import argparse
import asyncio

from bleak.exc import BleakError

from rower import (
    COUNTER_BYTE,
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
STEP_DELAY = 10.0

# (label, messages, expected state, required, seconds to poll afterwards).
# `end` is not required because the finished state has not been seen with our
# poll yet.
STEPS = (
    ("start", START, STATE_RUNNING, True, STEP_DELAY),
    ("pause", PAUSE, STATE_PAUSED, True, STEP_DELAY),
    ("resume", START, STATE_RUNNING, True, STEP_DELAY),
    ("stop: pause", PAUSE, STATE_PAUSED, True, 0),
    ("stop: end", END, STATE_FINISHED, False, 0),
    ("stop: idle", IDLE, STATE_IDLE, True, 0),
)


def log(text):
    print(f"[{timestamp()}] {text}", flush=True)


class Session:
    def __init__(self):
        self.assembler = MessageAssembler()
        self.state = None
        self.exit_code = 0
        self.task = None

    def feed(self, frame):
        message = self.assembler.feed(frame)
        if message is None or not message.checksum_ok:
            return
        body = message.body
        # The 50-byte telemetry message (`01 04 02 2e ...`), as in 03-telemetry.py.
        if body[:4] != b"\x01\x04\x02\x2e" or len(body) != 50 or body[10:18] == b"\xff" * 8:
            return
        state = body[STATE_BYTE]
        if state != self.state:
            name = STATE_NAMES.get(state, "?")
            log(f"State: {name} ({state}), counter {body[COUNTER_BYTE]} | {body.hex(' ')}")
            self.state = state

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

    async def run(self, rower):
        try:
            await self.play(rower)
        finally:
            rower.stopped.set()

    async def play(self, rower):
        if not await self.wait_state(None, STATE_TIMEOUT):
            log("No rower state received (no valid 50-byte message). Nothing sent.")
            self.exit_code = 1
            return
        for label, messages, expected, required, delay in STEPS:
            log(f"Sending {label}.")
            rower.queue(messages)
            if await self.wait_state(expected, CONFIRM_TIMEOUT):
                log(f"{label}: confirmed ({STATE_NAMES[expected]}).")
            elif required:
                log(f"{label}: state did not become {STATE_NAMES[expected]}. Stopping here.")
                self.exit_code = 1
                return
            else:
                log(f"{label}: state did not become {STATE_NAMES[expected]}, continuing.")
            await asyncio.sleep(delay)
        log("Session finished.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_connection_arguments(parser)
    parser.add_argument("--raw", action="store_true", help="also log every raw frame and every write")
    args = parser.parse_args()
    session = Session()
    try:
        code = asyncio.run(
            keep_alive(
                args.address,
                args.interval,
                args.duration,
                on_notification=session.feed,
                log_notifications=args.raw,
                log_writes=args.raw,
                on_ready=session.on_ready,
            )
        )
        raise SystemExit(code or session.exit_code)
    except (KeyboardInterrupt, BleakError) as error:
        log(f"Stopped: {error!r}")
