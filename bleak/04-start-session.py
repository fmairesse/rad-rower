"""Start a training session on the ProForm Sport RL rower, like iFit does.

Connects like 02-connection.py, waits until the rower reports its state, then
(only with --send) replays the iFit "start" sequence: a `17` message with the
flag byte at 0, then the `fe 02 0d 02` / `ff 0d ... 02 00 33` state write.
Nothing is written but the usual init and polls unless --send is given. Expected
effect, as seen with iFit: the rower beeps, its timer restarts from 0 and its
state (byte 25 of the 50-byte message) goes from 1 (idle) to 2 (running).

The start is refused unless the rower is idle (state 1), because a start from
"paused" resumes the session instead of restarting it. Use --force to override.
No stop or resistance command is ever sent: stop the session from the rower.

Usage: poetry run python 04-start-session.py [--send] [--observe 10] [--raw]
"""

import argparse
import asyncio

from bleak.exc import BleakError

from rower import (
    START,
    COUNTER_BYTE,
    STATE_BYTE,
    STATE_NAMES,
    MessageAssembler,
    add_connection_arguments,
    keep_alive,
    timestamp,
)

IDLE, RUNNING = 1, 2
STATE_TIMEOUT = 15.0
START_TIMEOUT = 5.0


def log(text):
    print(f"[{timestamp()}] {text}", flush=True)


class StartSession:
    def __init__(self, args):
        self.args = args
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

    async def wait_state(self, accepted, timeout):
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while loop.time() < deadline:
            if self.state is not None and accepted(self.state):
                return True
            await asyncio.sleep(0.1)
        return False

    async def run(self, rower):
        try:
            await self.start(rower)
        finally:
            rower.stopped.set()

    async def start(self, rower):
        if not await self.wait_state(lambda state: True, STATE_TIMEOUT):
            log("No rower state received (no valid 50-byte message). Nothing sent.")
            self.exit_code = 1
            return
        if self.state != IDLE and not self.args.force:
            log(f"Rower is {STATE_NAMES.get(self.state, '?')}, not idle: not starting. Use --force to override.")
            self.exit_code = 1
            return
        if not self.args.send:
            log("Dry run, would send:")
            for label, frames in START:
                for frame in frames:
                    log(f"  >> {frame.hex(' ')} // {label}")
            log("Run again with --send to start the session.")
            return

        log("Sending the start sequence.")
        rower.queue(START)
        if await self.wait_state(lambda state: state == RUNNING, START_TIMEOUT):
            log("Rower is running: start accepted. Check the beep and the timer on the rower.")
        else:
            log("The state did not become running: start not confirmed.")
            self.exit_code = 1
        await asyncio.sleep(self.args.observe)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    add_connection_arguments(parser)
    parser.add_argument("--send", action="store_true", help="really send the start command (default: dry run)")
    parser.add_argument("--force", action="store_true", help="start even if the rower is not idle")
    parser.add_argument("--observe", type=float, default=10, help="seconds to keep polling after the start")
    parser.add_argument("--raw", action="store_true", help="also log every raw frame and every write")
    args = parser.parse_args()
    session = StartSession(args)
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
