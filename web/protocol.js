// Port of bleak/rower.py and bleak/03-telemetry.py; see docs/protocole-ble.md.
export const SERVICE_UUID = "00001533-1412-efde-1523-785feabcd123";
export const WRITE_UUID = "00001534-1412-efde-1523-785feabcd123";
export const NOTIFY_UUID = "00001535-1412-efde-1523-785feabcd123";

const message = (...frames) => frames.map((hex) =>
  Uint8Array.from(hex.match(/../g), (byte) => Number.parseInt(byte, 16)));

export const SETUP = [
  message("fe020802", "ff08020402040204818700000000000000000000"),
  message("fe020802", "ff08020402041404809800000000000000000000"),
  message("fe020802", "ff0802040204140488a000000000000000000000"),
  message("fe020a02", "ff0a0204020602068200008a0000000000000000"),
  message("fe020a02", "ff0a0204020602068400008c0000000000000000"),
  message("fe020802", "ff08020402040204959b00000000000000000000"),
  message(
    "fe022c04",
    "0012020402281428900701f2f4f4f2fe08001e2a",
    "01123c4c5a669088a6c2e404224e98b0ce1a2c7c",
    "ff088ad620980200000000000000000000000000",
  ),
  message(
    "fe021703",
    "0012020402131413020c00000000000000000000",
    "ff0500800100b600000000000000000000000000",
  ),
  message(
    "fe021903",
    "0012020402151415020e00000000000000000000",
    "ff070000001001004a0000000000000000000000",
  ),
  message(
    "fe021903",
    "001202040215141502000f001000c01c4c0000e0",
    "ff070000001000086a0000000000000000000000",
  ),
];

export const POLL = [
  message(
    "fe021703",
    "001202040213141302000d1c9e31000040400080",
    "ff0500000081a200000000000000000000000000",
  ),
  message(
    "fe021903",
    "001202040215141502000f800040000000000000",
    "ff0700000000e011eb0000000000000000000000",
  ),
];

export const START = [
  message("fe021703", "0012020402131413020c00000000000000000000", "ff0500800000b500000000000000000000000000"),
  message("fe020d02", "ff0d020402091409020200100200330000000000"),
];
export const PAUSE = [message("fe020d02", "ff0d020402091409020200100300340000000000")];
export const END = [message("fe020d02", "ff0d020402091409020200100400350000000000")];
export const IDLE = [
  message("fe021703", "0012020402131413020c00000000000000000000", "ff0500800100b600000000000000000000000000"),
  message("fe020d02", "ff0d020402091409020200100100320000000000"),
];

const RESISTANCE_LEVELS = new Map([
  [0x01, 1], [0x03, 2], [0x04, 3], [0x06, 4], [0x07, 5], [0x08, 5],
  [0x09, 6], [0x0b, 7], [0x0c, 8], [0x0d, 8], [0x0e, 9], [0x10, 10],
  [0x11, 11], [0x13, 12], [0x14, 13], [0x15, 13], [0x16, 14], [0x18, 15],
  [0x19, 16], [0x1a, 16], [0x1b, 17], [0x1d, 18], [0x1e, 19], [0x20, 20],
  [0x22, 21], [0x23, 22], [0x25, 23], [0x26, 24], [0x27, 24],
]);

const u16 = (bytes, offset) => bytes[offset] | (bytes[offset + 1] << 8);
const u32 = (bytes, offset) => (u16(bytes, offset) + u16(bytes, offset + 2) * 65536);
const startsWith = (bytes, prefix) => prefix.every((byte, i) => bytes[i] === byte);
const paceFields = (paceSPer500M) => ({
  paceSPer500M: paceSPer500M || null,
  speedKmh: paceSPer500M ? 1800 / paceSPer500M : 0,
});

export class MessageAssembler {
  constructor() {
    this.reset();
  }

  reset() {
    this.body = [];
    this.length = 0;
    this.frames = 0;
    this.received = 0;
  }

  feed(frame) {
    if (frame.length !== 20) {
      this.reset();
      return null;
    }
    if (frame[0] === 0xfe && frame[1] === 0x02) {
      this.reset();
      this.length = frame[2];
      this.frames = frame[3];
      this.received = this.frames >= 2 ? 1 : 0;
      return null;
    }
    if (!this.received) return null;
    if (frame[0] === this.received - 1 && frame[1] === 0x12 &&
        this.received < this.frames - 1 && this.body.length + 18 <= this.length) {
      this.body.push(...frame.slice(2));
      this.received += 1;
      return null;
    }
    const count = frame[1];
    if (frame[0] === 0xff && this.received === this.frames - 1 &&
        count > 0 && count <= 18 && count === this.length - this.body.length) {
      this.body.push(...frame.slice(2, 2 + count));
      const body = Uint8Array.from(this.body);
      this.reset();
      const sum = body.slice(4, -1).reduce((total, byte) => total + byte, 0) & 0xff;
      return { body, checksumOk: body.length >= 2 && sum === body.at(-1) };
    }
    this.reset();
    return null;
  }
}

export function decodeMessage(body) {
  if (startsWith(body, [0x01, 0x04, 0x02]) &&
      [0x2e, 0x30, 0x31].includes(body[3]) && body.length === body[3] + 4) {
    if (body.slice(10, 18).every((byte) => byte === 0xff)) {
      return { powerW: null, distanceM: null, resistanceLevel: null, sessionState: null,
        elapsedS: null, caloriesKcal: null, averageWatts: null, paused: null, caloriesPerHour: null };
    }
    const powerW = u16(body, 10);
    const values = {
      powerW,
      distanceM: u16(body, 12),
      resistanceLevel: body[9] === 0 ? 1 : (RESISTANCE_LEVELS.get(body[9]) ?? null),
    };
    if (body[3] === 0x2e) {
      Object.assign(values, {
        sessionState: body[25], elapsedS: u16(body, 21),
        caloriesKcal: u32(body, 34) / 100000, averageWatts: body[40], paused: body[45],
        caloriesPerHour: powerW ? Math.round(3.2438 * powerW + 174.66) : null,
      });
    }
    return values;
  }
  if (body.length === 33 && startsWith(body, [1, 4, 2, 0x1d, 0x14, 0x1d, 2, 2])) {
    return paceFields(u16(body, 27));
  }
  return null;
}

export function decodeMotionFrame(frame) {
  if (frame.length !== 20 || frame[0] !== 0xff || frame[1] !== 0x11 ||
      frame.slice(2, 10).some((byte) => byte !== 0)) return null;
  return {
    strokesCount: u16(frame, 10),
    cadenceSpm: frame[12],
    ...paceFields(u16(frame, 13)),
    averagePace: u16(frame, 15) || null,
  };
}
