const API = "https://intervals.icu/api/v1/";
const localDateTime = (timestamp) => {
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
  }).formatToParts(timestamp);
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]));
  return `${values.year}-${values.month}-${values.day}T${values.hour}:${values.minute}:${values.second}`;
};

function activeDurationS(session, samples) {
  let pauseStartedEpochMs = null;
  let pausedMs = 0;
  let previousState = 2;
  for (const sample of samples) {
    const state = sample.metrics.sessionState;
    if (state === 3 && previousState !== 3) pauseStartedEpochMs = sample.timestamp;
    if (state === 2 && previousState === 3 && pauseStartedEpochMs !== null) {
      pausedMs += sample.timestamp - pauseStartedEpochMs;
      pauseStartedEpochMs = null;
    }
    if ([2, 3].includes(state)) previousState = state;
  }
  if (pauseStartedEpochMs !== null) pausedMs += session.endedAt - pauseStartedEpochMs;
  if (pausedMs === 0 && Number.isFinite(session.metrics.paused)) {
    pausedMs = session.metrics.paused * 1000;
  }
  return Math.max(0, (session.endedAt - session.startedAt - pausedMs) / 1000);
}

export function exportWorkout(session, samples) {
  if (session.status !== "completed" || !(session.endedAt > session.startedAt)) {
    throw new Error("Only completed workouts with a positive duration can be exported.");
  }
  const metrics = session.metrics || {};
  const movingTimeS = activeDurationS(session, samples);
  const distanceM = Number.isFinite(metrics.distanceM) ? metrics.distanceM : undefined;
  const averageWatts = Number.isFinite(metrics.averageWatts) ? metrics.averageWatts : undefined;
  const averageCadenceSpm = Number.isFinite(metrics.averageCadenceSpm) ? metrics.averageCadenceSpm : undefined;
  const caloriesKcal = Number.isFinite(metrics.caloriesKcal) ? metrics.caloriesKcal : undefined;
  return {
    start_date_local: localDateTime(session.startedAt),
    type: "Rowing",
    name: "Radrower - ProForm Sport RL",
    description: JSON.stringify({ app: "Radrower", sessionId: session.id,
      partial: Boolean(session.partial), rowerMetrics: metrics,
      note: "Raw telemetry remains stored locally in Radrower." }),
    device_name: "ProForm Sport RL",
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    trainer: true,
    external_id: `radrower-${session.id}`,
    elapsed_time: Math.round((session.endedAt - session.startedAt) / 1000),
    moving_time: Math.round(movingTimeS),
    ...(distanceM !== undefined ? { distance: distanceM } : {}),
    ...(averageWatts !== undefined ? { average_watts: averageWatts, device_watts: true } : {}),
    ...(averageCadenceSpm !== undefined ? { average_cadence: averageCadenceSpm } : {}),
    ...(caloriesKcal !== undefined ? { calories: caloriesKcal } : {}),
  };
}

export class IntervalsIcu {
  constructor(store, { fetch = globalThis.fetch.bind(globalThis), clock = Date.now } = {}) {
    this.store = store;
    this.fetch = fetch;
    this.clock = clock;
    this.apiKey = null;
    this.athleteId = null;
    this.syncing = false;
    this.generation = 0;
  }

  get connected() { return Boolean(this.apiKey && this.athleteId); }

  async connect(apiKey) {
    const key = apiKey.trim();
    if (!key) throw new Error("Enter an Intervals.icu API key.");
    this.apiKey = key;
    try {
      const athlete = await this.request("athlete/0");
      if (!athlete?.id) throw new Error("Intervals.icu did not return an athlete ID.");
      this.athleteId = athlete.id;
      this.generation++;
    } catch (error) {
      this.apiKey = null;
      this.athleteId = null;
      throw error;
    }
  }

  disconnect() {
    this.generation++;
    this.apiKey = null;
    this.athleteId = null;
  }

  async request(path, options = {}) {
    if (!this.apiKey) throw new Error("Connect Intervals.icu before syncing workouts.");
    const response = await this.fetch(`${API}${path}`, {
      ...options,
      cache: "no-store",
      signal: AbortSignal.timeout(20000),
      headers: {
        Authorization: `Basic ${btoa(`API_KEY:${this.apiKey}`)}`,
        ...(options.body ? { "Content-Type": "application/json" } : {}),
      },
    });
    const text = await response.text();
    let body;
    try { body = text ? JSON.parse(text) : null; } catch { body = null; }
    if (!response.ok) {
      const error = new Error(body?.message || body?.error ||
        `Intervals.icu returned HTTP ${response.status}.`);
      error.status = response.status;
      if (response.status === 401) {
        this.disconnect();
        error.message = "Intervals.icu rejected the API key. Reconnect to retry.";
      }
      throw error;
    }
    return body;
  }

  async findWorkout(session) {
    const day = localDateTime(session.startedAt).slice(0, 10);
    const query = new URLSearchParams({ oldest: day, newest: day, limit: "100" });
    const activities = await this.request(`athlete/${encodeURIComponent(this.athleteId)}/activities?${query}`);
    return activities?.find((activity) => activity.external_id === `radrower-${session.id}`) || null;
  }

  async sync() {
    if (!this.connected || this.syncing) return;
    this.syncing = true;
    const generation = this.generation;
    try {
      for (const session of await this.store.list()) {
        if (generation !== this.generation) break;
        if (session.intervalsSynced || session.status !== "completed") continue;
        const remote = await this.findWorkout(session);
        if (generation !== this.generation) break;
        let activity = remote;
        if (!activity) {
          const payload = exportWorkout(session, await this.store.samples(session.id));
          await this.store.update(session.id, { intervalsPending: true });
          try {
            activity = await this.request(
              `athlete/${encodeURIComponent(this.athleteId)}/activities/manual`,
              { method: "POST", body: JSON.stringify(payload) },
            );
          } catch (error) {
            const existing = await this.findWorkout(session).catch(() => null);
            if (!existing) {
              throw new Error(`${error.message} The workout is retained locally; retry to check its remote status.`);
            }
            activity = existing;
          }
        }
        if (generation !== this.generation) break;
        if (!activity?.id || (activity.external_id && activity.external_id !== `radrower-${session.id}`)) {
          throw new Error("Intervals.icu did not confirm the exported workout.");
        }
        await this.store.update(session.id, {
          intervalsSynced: true,
          intervalsSyncedAt: this.clock(),
          intervalsActivityId: activity.id,
          intervalsPending: false,
        });
      }
    } finally {
      this.syncing = false;
    }
  }
}