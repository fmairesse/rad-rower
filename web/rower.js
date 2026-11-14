import {
  SERVICE_UUID, WRITE_UUID, NOTIFY_UUID, SETUP, POLL,
  MessageAssembler, decodeMessage, decodeMotionFrame,
} from "./protocol.js";

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
      throw new Error("La connexion Bluetooth est interrompue.");
    }
  }

  async connect() {
    if (this.connecting || this.session) {
      throw new Error("Une connexion est déjà en cours.");
    }
    this.connecting = true;
    const session = {
      abort: new AbortController(),
      assembler: new MessageAssembler(),
      lastNotification: 0,
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
        new Error("Le rameur s’est déconnecté. Rapprochez-le puis reconnectez-vous."));
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
      this.onWarning(`Notification ignorée : ${frame.length} octets au lieu de 20.`);
      return;
    }
    const motion = decodeMotionFrame(frame);
    if (motion) this.onMetrics(motion);
    const message = session.assembler.feed(frame);
    if (!message) return;
    if (!message.checksumOk) {
      this.onWarning("Message ignoré : somme de contrôle incorrecte.");
      return;
    }
    const metrics = decodeMessage(message.body);
    if (metrics) this.onMetrics(metrics);
  }

  async send(session, frames) {
    for (const frame of frames) {
      this.check(session);
      await session.write.writeValueWithResponse(frame);
      this.check(session);
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
      session.abort.abort(new DOMException("Connexion annulée.", "AbortError"));
      session.notify?.removeEventListener("characteristicvaluechanged", session.notification);
      if (session.disconnected) {
        session.device.removeEventListener("gattserverdisconnected", session.disconnected);
      }
      session.device?.gatt?.disconnect();
    }
    this.onState("disconnected");
  }
}
