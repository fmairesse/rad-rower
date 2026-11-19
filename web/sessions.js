const SUMMARY_COUNTERS = ["distanceM", "caloriesKcal", "strokesCount", "elapsedS"];
const LEGACY_METRIC_KEYS = {
  watts: "powerW",
  distance: "distanceM",
  resistance: "resistanceLevel",
  elapsed: "elapsedS",
  calories: "caloriesKcal",
  strokes: "strokesCount",
  cadence: "cadenceSpm",
  pace: "paceSPer500M",
  speed: "speedKmh",
  averageCadence: "averageCadenceSpm",
  averageSpeed: "averageSpeedKmh",
};

function normalizeStoredMetrics(metrics) {
  return Object.fromEntries(Object.entries(metrics || {}).map(([key, value]) =>
    [LEGACY_METRIC_KEYS[key] ?? key, value]));
}

function normalizeStoredSession(session) {
  const metrics = normalizeStoredMetrics(session.metrics);
  if (session.metrics?.averagePace != null && metrics.averagePaceSPer500M == null) {
    metrics.averagePaceSPer500M = session.metrics.averagePace;
  }
  return { ...session, metrics };
}

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
      return () => request.result
        .map(normalizeStoredSession)
        .sort((first, second) => second.startedAt - first.startedAt);
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
      return () => request.result.map((sample) => ({
        ...sample,
        metrics: normalizeStoredMetrics(sample.metrics),
      }));
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
    this.activeMs = 0;
    this.lastRecordAtEpochMs = null;
    this.cadenceTotalSpm = 0;
    this.cadenceSampleCount = 0;
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
        partial: values.sessionState === 3 || Number(values.elapsedS) > 2,
        startUtcOffset: `${-new Date(timestamp).getTimezoneOffset() * 60}s`,
      };
      this.rowerState = values.sessionState;
      this.activeMs = 0;
      this.lastRecordAtEpochMs = timestamp;
      this.cadenceTotalSpm = 0;
      this.cadenceSampleCount = 0;
    }
    if (!this.current) return;
    if (this.lastRecordAtEpochMs !== null && this.rowerState === 2) {
      this.activeMs += timestamp - this.lastRecordAtEpochMs;
    }
    this.lastRecordAtEpochMs = timestamp;
    const sampleState = values.sessionState ?? this.rowerState;
    if (values.sessionState != null) this.rowerState = values.sessionState;
    const metrics = Object.fromEntries(Object.entries(values)
      .filter(([, value]) => value === null || typeof value === "number" || typeof value === "string"));
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
    if (sampleState === 2 && Number.isFinite(values.cadenceSpm) && values.cadenceSpm > 0) {
      this.cadenceTotalSpm += values.cadenceSpm;
      this.cadenceSampleCount += 1;
      this.current.metrics.averageCadenceSpm = Math.round(this.cadenceTotalSpm / this.cadenceSampleCount * 100) / 100;
    }
    const distanceM = this.current.metrics.distanceM;
    const activeS = this.activeMs / 1000;
    if (!this.current.partial && distanceM > 0 && activeS > 0) {
      this.current.metrics.averageSpeedKmh = Math.round(distanceM / activeS * 360) / 100;
      this.current.metrics.averagePaceSPer500M = Math.round(activeS * 50000 / distanceM) / 100;
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
    this.activeMs = 0;
    this.lastRecordAtEpochMs = null;
    this.cadenceTotalSpm = 0;
    this.cadenceSampleCount = 0;
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