import assert from "node:assert/strict";
import test from "node:test";
import { normalizeMetrics, RowerConnection } from "./rower.js";
import { SETUP, POLL, SERVICE_UUID, WRITE_UUID, NOTIFY_UUID } from "./protocol.js";

function fixture({ requestError, writeError, connectGate } = {}) {
  const writes = [];
  const writeTimes = [];
  const events = [];
  const errors = [];
  const warnings = [];
  const metrics = [];
  let requestOptions;
  let notificationsStarted = false;
  let pendingWrites = 0;
  const notify = new EventTarget();
  notify.startNotifications = async () => { notificationsStarted = true; };
  const write = {
    async writeValueWithResponse(frame) {
      assert.equal(notificationsStarted, true);
      assert.equal(pendingWrites, 0, "GATT writes must be sequential");
      pendingWrites += 1;
      try {
        if (writeError) throw writeError;
        await new Promise((resolve) => setTimeout(resolve, 1));
        writes.push(Array.from(frame));
        writeTimes.push(performance.now());
      } finally {
        pendingWrites -= 1;
      }
    },
  };
  const device = new EventTarget();
  const server = {
    connected: false,
    async connect() {
      if (connectGate) await connectGate;
      this.connected = true;
      return this;
    },
    disconnect() {
      if (!this.connected) return;
      this.connected = false;
      device.dispatchEvent(new Event("gattserverdisconnected"));
    },
    async getPrimaryService(uuid) {
      assert.equal(uuid, SERVICE_UUID);
      return {
        async getCharacteristic(characteristic) {
          assert.ok([WRITE_UUID, NOTIFY_UUID].includes(characteristic));
          return characteristic === WRITE_UUID ? write : notify;
        },
      };
    },
  };
  device.gatt = server;
  const connection = new RowerConnection({
    bluetooth: {
      async requestDevice(options) {
        requestOptions = options;
        if (requestError) throw requestError;
        return device;
      },
    },
    onState: (state) => events.push(state),
    onMetrics: (value) => metrics.push(value),
    onError: (error) => errors.push(error),
    onWarning: (warning) => warnings.push(warning),
  });
  return {
    connection, device, server, notify, writes, writeTimes, events, errors, warnings, metrics,
    get requestOptions() { return requestOptions; },
  };
}

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(predicate) {
  const deadline = performance.now() + 3000;
  while (!predicate()) {
    if (performance.now() > deadline) assert.fail("Timed out waiting for connection event");
    await delay(5);
  }
}

function emit(notify, frame) {
  // Exercise DataView offsets, not just buffers starting at byte zero.
  const padded = new Uint8Array(frame.length + 8);
  padded.set(frame, 4);
  notify.value = new DataView(padded.buffer, 4, frame.length);
  notify.dispatchEvent(new Event("characteristicvaluechanged"));
}

function emitState(notify, state) {
  const body = new Uint8Array(50);
  body.set([1, 4, 2, 0x2e, 0x14, 0x2e, 2, 2]);
  body[25] = state;
  body[49] = body.slice(4, -1).reduce((sum, byte) => sum + byte, 0) & 0xff;
  emit(notify, Uint8Array.from([0xfe, 2, 50, 4, ...new Uint8Array(16)]));
  emit(notify, Uint8Array.from([0, 18, ...body.slice(0, 18)]));
  emit(notify, Uint8Array.from([1, 18, ...body.slice(18, 36)]));
  emit(notify, Uint8Array.from([0xff, 14, ...body.slice(36), ...new Uint8Array(4)]));
}

async function confirmCommand(fixture, state, after) {
  await until(() => fixture.writes.slice(after).some((frame) =>
    frame[0] === 0xff && frame[1] === 0x0d && frame[12] === state));
  emitState(fixture.notify, state);
}

test("subscribes before exact init, alternates complete polls, stops and reconnects cleanly", async (t) => {
  const f = fixture();
  t.after(() => f.connection.disconnect());
  await f.connection.connect();
  assert.deepEqual(f.requestOptions, {
    filters: [{ name: "I_RW" }], optionalServices: [SERVICE_UUID],
  });
  assert.deepEqual(f.events, ["connecting", "initializing", "streaming"]);
  const expected = [...SETUP, POLL[0], POLL[1], POLL[0]].flat()
    .map((frame) => Array.from(frame));
  await until(() => f.writes.length >= expected.length);
  assert.deepEqual(f.writes.slice(0, expected.length), expected);
  assert.ok(f.writeTimes[28] - f.writeTimes[25] >= 550, "Poll spacing must be about 600 ms");
  assert.ok(f.writeTimes[31] - f.writeTimes[28] >= 550);
  f.connection.disconnect();
  const count = f.writes.length;
  await delay(650);
  assert.equal(f.writes.length, count);
  assert.equal(f.server.connected, false);
  assert.equal(f.events.at(-1), "disconnected");
  assert.deepEqual(f.errors, []);
  await f.connection.connect();
  assert.equal(f.events.at(-1), "streaming");
});

test("normalizes resistance and automatic-stop state before publishing metrics", () => {
  const normalized = normalizeMetrics({ sessionState: 10, resistanceLevel: null }, 12);
  assert.equal(normalized.metrics.sessionState, 1);
  assert.equal(normalized.metrics.resistanceLevel, 12);
  assert.equal(normalizeMetrics({}, 1).metrics.resistanceLevel, 1);
});

test("decodes offset DataViews, ignores invalid checksums and surfaces malformed notifications", async (t) => {
  const f = fixture();
  t.after(() => f.connection.disconnect());
  // Stop during init: enough to test the installed notification listener.
  const connecting = f.connection.connect();
  await until(() => f.events.includes("initializing"));
  const body = new Uint8Array(50);
  body.set([1, 4, 2, 0x2e, 0x14, 0x2e, 2, 2]);
  body[9] = 3;
  body[10] = 100;
  body[12] = 42;
  body[49] = body.slice(4, -1).reduce((sum, byte) => sum + byte, 0) & 0xff;
  const header = Uint8Array.from([0xfe, 2, 50, 4, ...new Uint8Array(16)]);
  const frames = [
    header,
    Uint8Array.from([0, 18, ...body.slice(0, 18)]),
    Uint8Array.from([1, 18, ...body.slice(18, 36)]),
    Uint8Array.from([0xff, 14, ...body.slice(36), ...new Uint8Array(4)]),
  ];
  frames.forEach((frame) => emit(f.notify, frame));
  assert.deepEqual(f.metrics, [{ powerW: 100, distanceM: 42, resistanceLevel: 2, sessionState: 0,
    elapsedS: 0, caloriesKcal: 0, averageWatts: 0, paused: 0, caloriesPerHour: 499 }]);
  frames.at(-1)[15] ^= 1;
  frames.forEach((frame) => emit(f.notify, frame));
  assert.equal(f.metrics.length, 1);
  assert.match(f.warnings.at(-1), /checksum/);
  emit(f.notify, new Uint8Array(4));
  assert.match(f.warnings.at(-1), /4 bytes/);
  f.connection.disconnect();
  frames.forEach((frame) => emit(f.notify, frame));
  assert.equal(f.metrics.length, 1);
  await connecting;
  assert.deepEqual(f.errors, []);
});

test("reports chooser cancellation and write failures without leaving a live connection", async () => {
  for (const options of [
    { requestError: new DOMException("Cancelled", "NotFoundError") },
    { writeError: new Error("Write failed") },
  ]) {
    const f = fixture(options);
    await f.connection.connect();
    assert.equal(f.events.at(-1), "disconnected");
    assert.equal(f.errors.length, 1);
    assert.equal(f.server.connected, false);
    assert.equal(f.connection.connecting, false);
    assert.equal(f.connection.session, null);
  }
});

test("cancels a pending GATT connection and closes it if it resolves late", async () => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const f = fixture({ connectGate: gate });
  const connecting = f.connection.connect();
  await delay(0);
  f.connection.disconnect();
  release();
  await connecting;
  assert.equal(f.server.connected, false);
  assert.equal(f.writes.length, 0);
  assert.deepEqual(f.errors, []);
});

test("reports unexpected disconnection and stops writes", async () => {
  const f = fixture();
  const connecting = f.connection.connect();
  await until(() => f.events.includes("initializing"));
  f.server.disconnect();
  await connecting;
  const count = f.writes.length;
  await delay(200);
  assert.equal(f.writes.length, count);
  assert.equal(f.errors.length, 1);
  assert.match(f.errors[0].message, /disconnected/);
  assert.equal(f.events.at(-1), "disconnected");
});

test("confirms start, pause, resume and stop without overlapping poll writes", async (t) => {
  const f = fixture();
  t.after(() => f.connection.disconnect());
  await f.connection.connect();
  await assert.rejects(f.connection.control("toggle"), /unavailable/);
  emitState(f.notify, 1);
  for (const expected of [2, 3, 2]) {
    const after = f.writes.length;
    const command = f.connection.control("toggle");
    await assert.rejects(f.connection.control("toggle"), /already in progress/);
    await confirmCommand(f, expected, after);
    await command;
  }
  const after = f.writes.length;
  const stop = f.connection.control("stop");
  await confirmCommand(f, 3, after);
  await confirmCommand(f, 4, after);
  await confirmCommand(f, 1, after);
  await stop;
  assert.deepEqual(f.errors, []);
});

test("stops a paused session without another pause and cancels on disconnect", async (t) => {
  const f = fixture();
  t.after(() => f.connection.disconnect());
  await f.connection.connect();
  emitState(f.notify, 3);
  const after = f.writes.length;
  const stop = f.connection.control("stop");
  await confirmCommand(f, 4, after);
  await confirmCommand(f, 1, after);
  await stop;
  assert.equal(f.writes.slice(after).filter((frame) => frame[0] === 0xff &&
    frame[1] === 0x0d && frame[12] === 3).length, 0);
  emitState(f.notify, 1);
  const command = f.connection.control("toggle");
  const rejected = assert.rejects(command, /Connection cancelled/);
  await confirmCommand(f, 2, f.writes.length);
  f.connection.disconnect();
  await rejected;
});

test("does not continue stopping when pause is not confirmed", async (t) => {
  const f = fixture();
  t.after(() => f.connection.disconnect());
  await f.connection.connect();
  emitState(f.notify, 2);
  const after = f.writes.length;
  await assert.rejects(f.connection.control("stop"), /did not confirm/);
  const commands = f.writes.slice(after).filter((frame) => frame[0] === 0xff && frame[1] === 0x0d);
  assert.deepEqual(commands.map((frame) => frame[12]), [3]);
});
