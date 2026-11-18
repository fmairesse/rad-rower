import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  SETUP, POLL, START, PAUSE, END, IDLE, MessageAssembler, decodeMessage, decodeMotionFrame,
} from "./protocol.js";

function seal(body) {
  body[body.length - 1] = body.slice(4, -1).reduce((sum, byte) => sum + byte, 0) & 0xff;
  return body;
}

export function telemetryBody() {
  const body = new Uint8Array(50);
  body.set([1, 4, 2, 0x2e, 0x14, 0x2e, 2, 2]);
  body[9] = 0x26;
  body[10] = 0x2c;
  body[11] = 1;
  body[12] = 0xd2;
  body[13] = 4;
  return seal(body);
}

export function frameMessage(body) {
  const frames = [];
  const header = new Uint8Array(20).fill(0xa5);
  header.set([0xfe, 2, body.length, Math.ceil(body.length / 18) + 1]);
  frames.push(header);
  for (let offset = 0; offset < body.length; offset += 18) {
    const frame = new Uint8Array(20);
    const count = Math.min(18, body.length - offset);
    const last = offset + count === body.length;
    frame.set([last ? 0xff : offset / 18, last ? count : 0x12]);
    frame.set(body.slice(offset, offset + count), 2);
    frames.push(frame);
  }
  return frames;
}

export function motionFrame() {
  const frame = new Uint8Array(20);
  frame.set([0xff, 0x11]);
  frame[10] = 37;
  frame[12] = 24;
  frame[13] = 0x13;
  frame[14] = 1;
  frame[18] = (37 + 24 + 0x13 + 1 + 0x2f) & 0xff;
  frame[19] = 0xff;
  return frame;
}

test("all init and poll bytes match the validated Bleak reference", async () => {
  const source = await readFile(new URL("../bleak/rower.py", import.meta.url), "utf8");
  const headers = Object.fromEntries([...source.matchAll(/^(H\w+) = "([a-f0-9]+)"/gm)]
    .map((match) => [match[1], match[2]]));
  const reference = [...source.matchAll(/\bmessage\(([\s\S]*?)\)/g)]
    .map((match) => [...match[1].matchAll(/"([a-f0-9]{8,})"|\b(H\w+)\b/g)]
      .map((token) => token[1] ?? headers[token[2]]))
    .filter((frames) => frames.length);
  const actual = [...SETUP, ...POLL, ...START, ...PAUSE, ...END, ...IDLE]
    .map((frames) => frames.map((frame) => Buffer.from(frame).toString("hex")));
  assert.deepEqual(actual, reference);
  assert.equal(SETUP.flat().length, 25);
  assert.deepEqual(POLL.map((frames) => frames.length), [3, 3]);
});

test("reassembles telemetry, ignores stale header padding and checks checksum", () => {
  const assembler = new MessageAssembler();
  const frames = frameMessage(telemetryBody());
  for (const frame of frames.slice(0, -1)) assert.equal(assembler.feed(frame), null);
  const result = assembler.feed(frames.at(-1));
  assert.equal(result.checksumOk, true);
  assert.deepEqual(decodeMessage(result.body), { watts: 300, distance: 1234, resistance: 24,
    sessionState: 0, elapsed: 0, calories: 0, averageWatts: 0, paused: 0, caloriesPerHour: 1148 });
  frames[1][12] ^= 1;
  let corrupted;
  for (const frame of frames) corrupted = assembler.feed(frame);
  assert.equal(corrupted.checksumOk, false);
});

test("recovers after missing, out-of-order, truncated and oversized frames", () => {
  const assembler = new MessageAssembler();
  const frames = frameMessage(telemetryBody());
  for (const invalid of [
    [frames[0], frames[2], frames[3]],
    [frames[0], frames[1], new Uint8Array(19), frames[3]],
    [frames[0], frames[1], new Uint8Array(21), frames[3]],
    [frames[0], frames[1], frames[3]],
    [frames[0], frames[1], frames[2], Uint8Array.from([0xff, 19, ...new Uint8Array(18)])],
  ]) {
    for (const frame of invalid) assert.equal(assembler.feed(frame), null);
    let result;
    for (const frame of frames) result = assembler.feed(frame);
    assert.equal(result.checksumOk, true);
  }
  assert.equal(assembler.feed(frames.at(-1)), null);
});

test("decodes motion, zero pace and little-endian stroke count", () => {
  const frame = motionFrame();
  assert.deepEqual(decodeMotionFrame(frame), {
    strokes: 37, cadence: 24, pace: 275, speed: 1800 / 275, averagePace: null,
  });
  frame[11] = 1;
  frame[13] = 0;
  frame[14] = 0;
  assert.deepEqual(decodeMotionFrame(frame), {
    strokes: 293, cadence: 24, pace: null, speed: 0, averagePace: null,
  });
  frame[2] = 1;
  assert.equal(decodeMotionFrame(frame), null);
  assert.equal(decodeMotionFrame(new Uint8Array(19)), null);
});

test("handles unset, unknown and all validated resistance encodings without inventing levels", () => {
  const body = telemetryBody();
  body[9] = 0;
  assert.equal(decodeMessage(body).resistance, 1);
  body[9] = 0xff;
  assert.equal(decodeMessage(body).resistance, "? 0xff");
  const rawLevels = [
    1, 3, 4, 6, 7, 8, 9, 11, 12, 13, 14, 16, 17, 19, 20, 21, 22,
    24, 25, 26, 27, 29, 30, 32, 34, 35, 37, 38, 39,
  ];
  const expected = [
    1, 2, 3, 4, 5, 5, 6, 7, 8, 8, 9, 10, 11, 12, 13, 13, 14,
    15, 16, 16, 17, 18, 19, 20, 21, 22, 23, 24, 24,
  ];
  rawLevels.forEach((raw, index) => {
    body[9] = raw;
    assert.equal(decodeMessage(body).resistance, expected[index]);
  });
  body.fill(0xff, 10, 18);
  assert.deepEqual(decodeMessage(body), { watts: null, distance: null, resistance: null,
    sessionState: null, elapsed: null, calories: null, averageWatts: null, paused: null, caloriesPerHour: null });
});

test("decodes session telemetry and average pace using the Python offsets", () => {
  const body = telemetryBody();
  body[21] = 0x2c; body[22] = 1; body[25] = 3;
  body[34] = 0xa0; body[35] = 0x86; body[36] = 1;
  body[40] = 85; body[45] = 12;
  const values = decodeMessage(body);
  assert.equal(values.elapsed, 300);
  assert.equal(values.sessionState, 3);
  assert.equal(values.calories, 1);
  assert.equal(values.averageWatts, 85);
  assert.equal(values.paused, 12);
  assert.equal(values.caloriesPerHour, 1148);
  body[10] = 0; body[11] = 0;
  assert.equal(decodeMessage(body).caloriesPerHour, null);
  const frame = motionFrame();
  frame[15] = 0x4a; frame[16] = 1;
  assert.equal(decodeMotionFrame(frame).averagePace, 330);
});

test("handles alternate 33-byte pace messages and rejects unknown payloads", () => {
  const body = new Uint8Array(33);
  body.set([1, 4, 2, 0x1d, 0x14, 0x1d, 2, 2]);
  body[27] = 0x4a;
  body[28] = 1;
  assert.deepEqual(decodeMessage(body), { pace: 330, speed: 1800 / 330 });
  assert.equal(decodeMessage(new Uint8Array()), null);
  assert.equal(decodeMessage(body.slice(0, 20)), null);
});
