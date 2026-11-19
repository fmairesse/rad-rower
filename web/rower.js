import {
  SERVICE_UUID, WRITE_UUID, NOTIFY_UUID, SETUP, POLL, START, PAUSE, END, IDLE,
  MessageAssembler, decodeMessage, decodeMotionFrame,
} from "./protocol.js";

export function normalizeMetrics(metrics, previousResistanceLevel = 1) {
  const resistanceLevel = Number.isInteger(metrics.resistanceLevel) &&
    metrics.resistanceLevel >= 1 && metrics.resistanceLevel <= 24
    ? metrics.resistanceLevel
    : previousResistanceLevel;
  const normalized = { ...metrics, resistanceLevel };
  if (normalized.sessionState === 10) normalized.sessionState = 1;
  return { metrics: normalized, resistanceLevel };
}

const wait = (milliseconds, signal) => new Promise((resolve, reject) => {
  if (signal.aborted) {
    reject(signal.reason);
    return;
  }
  const abort = () => {
    clearTimeout(timer);
    reject(signal.reason);
  };
  const timer = setTimeout(() => {
    signal.removeEventListener("abort", abort);
    resolve();
  }, milliseconds);
  signal.addEventListener("abort", abort, { once: true });
});

export class RowerConnection {
  constructor({ bluetooth, onState, onMetrics, onWarning, onError }) {
    this.bluetooth = bluetooth;
    this.onState = onState;
    this.onMetrics = onMetrics;
    this.onWarning = onWarning;
    this.onError = onError;
    this.session = null;
    this.connecting = false;
  }

  check(session) {
    session.abort.signal.throwIfAborted();
    if (session !== this.session || !session.device?.gatt.connected) {
      throw new Error("The Bluetooth connection was interrupted.");
    }
  }

  async connect() {
    if (this.connecting || this.session) {
      throw new Error("A connection is already in progress.");
    }
    this.connecting = true;
    const session = {
      abort: new AbortController(),
      assembler: new MessageAssembler(),
      lastNotification: 0,
      writes: Promise.resolve(),
      stateVersion: 0,
      stateAt: 0,
      controlling: false,
      ready: false,
      resistanceLevel: 1,
    };
    this.session = session;
    this.onState("connecting");
    try {
      session.device = await this.bluetooth.requestDevice({
        filters: [{ name: "I_RW" }],
        optionalServices: [SERVICE_UUID],
      });
      session.abort.signal.throwIfAborted();
      session.disconnected = () => this.fail(session,
        new Error("The rower disconnected. Move it closer and reconnect."));
      session.device.addEventListener("gattserverdisconnected", session.disconnected);
      const server = await session.device.gatt.connect();
      if (session.abort.signal.aborted) {
        server.disconnect();
        session.abort.signal.throwIfAborted();
      }
      this.check(session);
      const service = await server.getPrimaryService(SERVICE_UUID);
      this.check(session);
      session.write = await service.getCharacteristic(WRITE_UUID);
      this.check(session);
      session.notify = await service.getCharacteristic(NOTIFY_UUID);
      this.check(session);
      session.notification = (event) => this.receive(session, event.target.value);
      session.notify.addEventListener("characteristicvaluechanged", session.notification);
      await session.notify.startNotifications();
      this.check(session);
      this.onState("initializing");
      for (const frames of SETUP) {
        await this.send(session, frames);
        const deadline = performance.now() + 1500;
        await wait(150, session.abort.signal);
        while (performance.now() < deadline &&
               performance.now() - session.lastNotification < 150) {
          await wait(50, session.abort.signal);
        }
      }
      this.check(session);
      session.ready = true;
      this.onState("streaming");
      void this.poll(session).catch((error) => this.fail(session, error));
    } catch (error) {
      this.fail(session, error);
    } finally {
      this.connecting = false;
    }
  }

  receive(session, value) {
    if (session !== this.session) return;
    session.lastNotification = performance.now();
    const frame = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    if (frame.length !== 20) {
      session.assembler.reset();
      this.onWarning(`Notification ignored: ${frame.length} bytes instead of 20.`);
      return;
    }
    const motion = decodeMotionFrame(frame);
    if (motion) this.publishMetrics(session, motion);
    const message = session.assembler.feed(frame);
    if (!message) return;
    if (!message.checksumOk) {
      this.onWarning("Message ignored: invalid checksum.");
      return;
    }
    const metrics = decodeMessage(message.body);
    if (metrics) this.publishMetrics(session, metrics);
  }

  publishMetrics(session, values) {
    const normalized = normalizeMetrics(values, session.resistanceLevel);
    session.resistanceLevel = normalized.resistanceLevel;
    const metrics = normalized.metrics;
    if (Object.hasOwn(metrics, "sessionState")) {
      session.rowerState = metrics.sessionState;
      session.stateAt = performance.now();
      session.stateVersion += 1;
    }
    this.onMetrics(metrics);
  }

  send(session, frames) {
    const operation = session.writes.then(async () => {
      for (const frame of frames) {
        this.check(session);
        await session.write.writeValueWithResponse(frame);
        this.check(session);
      }
    });
    session.writes = operation.catch(() => {});
    return operation;
  }

  async command(session, messages, expected, required = true) {
    const version = session.stateVersion;
    await this.send(session, messages.flat());
    const deadline = performance.now() + 5000;
    while (performance.now() < deadline) {
      this.check(session);
      if (session.stateVersion > version && session.rowerState === expected) return;
      await wait(100, session.abort.signal);
    }
    if (required) throw new Error("The rower did not confirm the state change. Check its console before trying again.");
  }

  async control(action) {
    const session = this.session;
    if (!session || !session.ready || session.controlling ||
        performance.now() - session.stateAt > 5000 || !session.stateAt) {
      throw new Error("Rower state unavailable or command already in progress.");
    }
    const state = session.rowerState;
    if (action === "toggle" && ![1, 2, 3].includes(state) ||
        action === "stop" && ![2, 3].includes(state) || !["toggle", "stop"].includes(action)) {
      throw new Error("This command is not available in the rower's current state.");
    }
    session.controlling = true;
    try {
      if (action === "toggle") {
        await this.command(session, state === 2 ? PAUSE : START, state === 2 ? 3 : 2);
      } else {
        if (state === 2) await this.command(session, PAUSE, 3);
        await this.command(session, END, 4, false);
        await this.command(session, IDLE, 1);
      }
    } finally {
      session.controlling = false;
    }
  }

  async poll(session) {
    let index = 0;
    let nextAt = performance.now();
    while (!session.abort.signal.aborted) {
      await this.send(session, POLL[index % POLL.length]);
      index += 1;
      nextAt += 600;
      // Do not burst old polls after a suspended browser tab resumes.
      if (nextAt < performance.now()) nextAt = performance.now() + 600;
      await wait(Math.max(0, nextAt - performance.now()), session.abort.signal);
    }
  }

  fail(session, error) {
    if (session !== this.session) return;
    this.disconnect();
    this.onError(error);
  }

  disconnect() {
    const session = this.session;
    this.session = null;
    if (session) {
      session.abort.abort(new DOMException("Connection cancelled.", "AbortError"));
      session.notify?.removeEventListener("characteristicvaluechanged", session.notification);
      if (session.disconnected) {
        session.device.removeEventListener("gattserverdisconnected", session.disconnected);
      }
      session.device?.gatt?.disconnect();
    }
    this.onState("disconnected");
  }
}
