const SUMMARY_COUNTERS = ["distance", "calories", "strokes", "elapsed", "paused"];
const OMITTED_SAMPLE_METRICS = new Set(["averagePace", "caloriesPerHour"]);

export class SessionStore {
  constructor(indexedDB = globalThis.indexedDB) {
    this.database = new Promise((resolve, reject) => {
      if (!indexedDB) return reject(new Error("IndexedDB is unavailable."));
      const request = indexedDB.open("radrower", 1);
      request.onupgradeneeded = () => {
        const database = request.result;
        database.createObjectStore("sessions", { keyPath: "id" });
        const samples = database.createObjectStore("samples", { keyPath: ["sessionId", "sequence"] });
        samples.createIndex("sessionId", "sessionId");
      };
      request.onsuccess = () => {
        request.result.onversionchange = () => request.result.close();
        resolve(request.result);
      };
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error("Close other Radrower tabs to update storage."));
    });
  }

  async transaction(names, mode, operation) {
    const database = await this.database;
    return new Promise((resolve, reject) => {
      const transaction = database.transaction(names, mode);
      let result;
      transaction.oncomplete = () => resolve(typeof result === "function" ? result() : result);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error || new Error("Storage transaction aborted."));
      result = operation(transaction);
    });
  }

  save(session, sample) {
    return this.transaction(["sessions", "samples"], "readwrite", (transaction) => {
      transaction.objectStore("sessions").put(session);
      if (sample) transaction.objectStore("samples").put(sample);
    });
  }

  list() {
    return this.transaction(["sessions"], "readonly", (transaction) => {
      const request = transaction.objectStore("sessions").getAll();
      return () => request.result.sort((first, second) => second.startedAt - first.startedAt);
    });
  }

  update(id, changes) {
    return this.transaction(["sessions"], "readwrite", (transaction) => {
      const store = transaction.objectStore("sessions");
      const request = store.get(id);
      request.onsuccess = () => {
        if (!request.result) return transaction.abort();
        store.put({ ...request.result, ...changes });
      };
    });
  }

  samples(id) {
    return this.transaction(["samples"], "readonly", (transaction) => {
      const request = transaction.objectStore("samples").index("sessionId").getAll(id);
      return () => request.result;
    });
  }
}

export class SessionRecorder {
  constructor(store, { onChange = () => {}, onError = () => {}, clock = Date.now,
    makeId = () => crypto.randomUUID() } = {}) {
    this.store = store;
    this.onChange = onChange;
    this.onError = onError;
    this.clock = clock;
    this.makeId = makeId;
    this.current = null;
    this.pending = Promise.resolve();
    this.rowerState = null;
    this.activeMilliseconds = 0;
    this.lastRecordAt = null;
    this.cadenceTotal = 0;
    this.cadenceSamples = 0;
  }

  persist(sample) {
    const session = structuredClone(this.current);
    this.pending = this.pending.then(async () => {
      await this.store.save(session, sample);
      await this.onChange(session);
    }).catch(this.onError);
  }

  record(values) {
    const timestamp = this.clock();
    if (!this.current && [2, 3].includes(values.sessionState)) {
      this.current = {
        id: this.makeId(), startedAt: timestamp, endedAt: null,
        status: "recording", synced: false, sampleCount: 0, metrics: {},
        partial: values.sessionState === 3 || Number(values.elapsed) > 2,
        startUtcOffset: `${-new Date(timestamp).getTimezoneOffset() * 60}s`,
      };
      this.rowerState = values.sessionState;
      this.activeMilliseconds = 0;
      this.lastRecordAt = timestamp;
      this.cadenceTotal = 0;
      this.cadenceSamples = 0;
    }
    if (!this.current) return;
    if (this.lastRecordAt !== null && this.rowerState === 2) {
      this.activeMilliseconds += timestamp - this.lastRecordAt;
    }
    this.lastRecordAt = timestamp;
    const sampleState = values.sessionState ?? this.rowerState;
    if (values.sessionState != null) this.rowerState = values.sessionState;
    const metrics = Object.fromEntries(Object.entries(values)
      .filter(([key, value]) => !OMITTED_SAMPLE_METRICS.has(key) &&
        (value === null || typeof value === "number" || typeof value === "string")));
    const finishing = [1, 4, 10].includes(values.sessionState);
    for (const key of SUMMARY_COUNTERS) {
      const value = values[key];
      if (Number.isFinite(value) && value >= 0) {
        this.current.metrics[key] = Math.max(this.current.metrics[key] ?? 0, value);
      }
    }
    if (Number.isFinite(values.averageWatts) && values.averageWatts > 0) {
      this.current.metrics.averageWatts = values.averageWatts;
    }
    if (sampleState === 2 && Number.isFinite(values.cadence) && values.cadence > 0) {
      this.cadenceTotal += values.cadence;
      this.cadenceSamples += 1;
      this.current.metrics.averageCadence = Math.round(this.cadenceTotal / this.cadenceSamples * 100) / 100;
    }
    const distance = this.current.metrics.distance;
    const activeSeconds = this.activeMilliseconds / 1000;
    if (!this.current.partial && distance > 0 && activeSeconds > 0) {
      this.current.metrics.averageSpeed = Math.round(distance / activeSeconds * 360) / 100;
      this.current.metrics.averagePace = Math.round(activeSeconds * 50000 / distance) / 100;
    }
    const sample = {
      sessionId: this.current.id, sequence: this.current.sampleCount++, timestamp, metrics,
      utcOffset: `${-new Date(timestamp).getTimezoneOffset() * 60}s`,
    };
    this.persist(sample);
    if (finishing) this.finish("completed", timestamp);
  }

  finish(status = "completed", timestamp = this.clock()) {
    if (!this.current) return this.pending;
    this.current.status = status;
    this.current.endedAt = timestamp;
    this.current.endUtcOffset = `${-new Date(timestamp).getTimezoneOffset() * 60}s`;
    this.persist();
    this.current = null;
    this.rowerState = null;
    this.activeMilliseconds = 0;
    this.lastRecordAt = null;
    this.cadenceTotal = 0;
    this.cadenceSamples = 0;
    return this.pending;
  }

  async recover() {
    for (const session of await this.store.list()) {
      if (session.status !== "recording") continue;
      const samples = await this.store.samples(session.id);
      session.status = "interrupted";
      session.endedAt = samples.at(-1)?.timestamp ?? session.startedAt;
      await this.store.save(session);
    }
  }
}