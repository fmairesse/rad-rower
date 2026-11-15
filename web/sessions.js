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
      };
    }
    if (!this.current) return;
    const metrics = Object.fromEntries(Object.entries(values)
      .filter(([, value]) => value === null || typeof value === "number" || typeof value === "string"));
    const finishing = [1, 4, 10].includes(values.sessionState);
    if (!finishing) {
      for (const [key, value] of Object.entries(metrics)) {
        if (value !== null) this.current.metrics[key] = value;
      }
    }
    const sample = {
      sessionId: this.current.id, sequence: this.current.sampleCount++, timestamp, metrics,
    };
    this.persist(sample);
    if (finishing) this.finish("completed", timestamp);
  }

  finish(status = "completed", timestamp = this.clock()) {
    if (!this.current) return this.pending;
    this.current.status = status;
    this.current.endedAt = timestamp;
    this.persist();
    this.current = null;
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