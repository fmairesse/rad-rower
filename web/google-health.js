export const GOOGLE_CLIENT_ID = "680149518567-fb9ttp9a6crkot5mq2bnpsstors4s31s.apps.googleusercontent.com";
export const HEALTH_SCOPE = "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.writeonly";
const API = "https://health.googleapis.com/v4/";
const date = (timestamp) => new Date(timestamp).toISOString();
const offset = (timestamp) => `${-new Date(timestamp).getTimezoneOffset() * 60}s`;
const duration = (seconds) => `${Number(seconds.toFixed(3))}s`;

export function exportWorkout(session, samples) {
  if (session.status !== "completed" || !(session.endedAt > session.startedAt)) {
    throw new Error("Only completed workouts with a positive duration can be synced.");
  }
  const events = [];
  let previousState = 2;
  let pauseStartedEpochMs = null;
  let pausedMs = 0;
  const statistics = {};
  for (const sample of samples) {
    const state = sample.metrics.sessionState;
    if (state === 3 && previousState !== 3) {
      pauseStartedEpochMs = sample.timestamp;
      events.push({ timestamp: sample.timestamp, type: "PAUSE", utcOffset: sample.utcOffset });
    } else if (state === 2 && previousState === 3) {
      pausedMs += sample.timestamp - pauseStartedEpochMs;
      pauseStartedEpochMs = null;
      events.push({ timestamp: sample.timestamp, type: "RESUME", utcOffset: sample.utcOffset });
    }
    if ([2, 3].includes(state)) previousState = state;
    for (const [key, value] of Object.entries(sample.metrics)) {
      if (!Number.isFinite(value) || key === "sessionState") continue;
      const entry = statistics[key] ||= { min: value, max: value, sum: 0, count: 0 };
      entry.min = Math.min(entry.min, value);
      entry.max = Math.max(entry.max, value);
      entry.sum += value;
      entry.count++;
    }
  }
  if (pauseStartedEpochMs !== null) pausedMs += session.endedAt - pauseStartedEpochMs;
  const activeS = Math.max(0, (session.endedAt - session.startedAt - pausedMs) / 1000);
  const summary = {};
  const { distanceM, caloriesKcal } = session.metrics;
  if (Number.isFinite(distanceM) && distanceM >= 0) {
    summary.distanceMillimeters = distanceM * 1000;
    if (distanceM > 0 && activeS > 0 && !session.partial) {
      summary.averageSpeedMillimetersPerSecond = distanceM * 1000 / activeS;
      summary.averagePaceSecondsPerMeter = activeS / distanceM;
    }
  }
  if (Number.isFinite(caloriesKcal) && caloriesKcal >= 0) summary.caloriesKcal = caloriesKcal;
  const sampledStatistics = Object.fromEntries(Object.entries(statistics).map(([key, entry]) =>
    [key, { min: entry.min, max: entry.max, sampleMean: entry.sum / entry.count }]));
  return {
    dataSource: {
      recordingMethod: "ACTIVELY_MEASURED",
      device: { manufacturer: "ProForm", displayName: "Sport RL" },
    },
    exercise: {
      displayName: "Rowing machine", exerciseType: "ROWING_MACHINE",
      interval: {
        startTime: date(session.startedAt), endTime: date(session.endedAt),
        startUtcOffset: session.startUtcOffset ?? offset(session.startedAt),
        endUtcOffset: session.endUtcOffset ?? offset(session.endedAt),
      },
      activeDuration: duration(activeS), metricsSummary: summary,
      exerciseEvents: [
        { eventTime: date(session.startedAt), eventUtcOffset: session.startUtcOffset ?? offset(session.startedAt), exerciseEventType: "START" },
        ...events.map((event) => ({ eventTime: date(event.timestamp),
          eventUtcOffset: event.utcOffset ?? offset(event.timestamp), exerciseEventType: event.type })),
        { eventTime: date(session.endedAt), eventUtcOffset: session.endUtcOffset ?? offset(session.endedAt), exerciseEventType: "STOP" },
      ],
      exerciseMetadata: { hasGps: false },
      notes: JSON.stringify({ app: "Radrower", sessionId: session.id, partial: session.partial,
        units: { averageWatts: "W", averageCadenceSpm: "strokes/min", strokesCount: "strokes",
          averageSpeedKmh: "km/h", averagePaceSPer500M: "s/500m", averagePace: "s/500m",
          distanceM: "m", elapsedS: "s", paused: "s", caloriesKcal: "kcal" },
        rowerMetrics: session.metrics, sampledStatistics,
        caveats: "Rower counters; partial recordings may omit earlier samples. Average cadence is a sample mean; average speed and pace use recorded active time and distance. Average power interpretation is provisional. Raw samples remain on the device." }),
    },
  };
}

export class GoogleHealth {
  constructor(store, { oauth = () => globalThis.google?.accounts?.oauth2,
    fetch = globalThis.fetch.bind(globalThis), clock = Date.now } = {}) {
    this.store = store;
    this.oauth = oauth;
    this.fetch = fetch;
    this.clock = clock;
    this.token = null;
    this.expiresAt = 0;
    this.generation = 0;
    this.syncing = false;
  }

  get connected() { return Boolean(this.token) && this.clock() < this.expiresAt; }

  connect() {
    const oauth = this.oauth();
    if (!oauth) return Promise.reject(new Error("Google sign-in is unavailable. Check your connection and try again."));
    const generation = ++this.generation;
    return new Promise((resolve, reject) => {
      const client = oauth.initTokenClient({
        client_id: GOOGLE_CLIENT_ID, scope: HEALTH_SCOPE, include_granted_scopes: false,
        callback: (response) => {
          if (generation !== this.generation) return reject(new Error("Google connection cancelled."));
          if (response.error || !response.access_token || !oauth.hasGrantedAllScopes(response, HEALTH_SCOPE)) {
            return reject(new Error("Google Health permission was not granted."));
          }
          this.token = response.access_token;
          this.expiresAt = this.clock() + Math.max(0, Number(response.expires_in) - 60) * 1000;
          resolve();
        },
        error_callback: () => reject(new Error("Google sign-in was closed or blocked. Try again.")),
      });
      client.requestAccessToken({ prompt: "" });
    });
  }

  disconnect() {
    this.generation++;
    this.token = null;
    this.expiresAt = 0;
  }

  async request(path, options = {}) {
    if (!this.connected) throw new Error("Reconnect Google Health to sync your saved workouts.");
    const response = await this.fetch(`${API}${path}`, {
      ...options, cache: "no-store", signal: AbortSignal.timeout(20000),
      headers: { Authorization: `Bearer ${this.token}`, "Content-Type": "application/json" },
    });
    if (response.status === 401) {
      this.disconnect();
      throw new Error("Google authorization expired. Reconnect to retry.");
    }
    if (response.status === 404) return null;
    const body = await response.json();
    if (!response.ok) {
      const error = new Error(body.error?.message || `Google Health returned HTTP ${response.status}.`);
      error.status = response.status;
      throw error;
    }
    return body;
  }

  async findWorkout(session) {
    const offsetSeconds = Number.parseInt(session.startUtcOffset || "0s", 10);
    const localStart = new Date(session.startedAt + offsetSeconds * 1000);
    const dayStart = localStart.toISOString().slice(0, 10);
    const nextDay = new Date(Date.UTC(localStart.getUTCFullYear(), localStart.getUTCMonth(),
      localStart.getUTCDate() + 1)).toISOString().slice(0, 10);
    const filter = `exercise.interval.civil_start_time >= "${dayStart}" AND exercise.interval.civil_start_time < "${nextDay}"`;
    const query = new URLSearchParams({ filter, pageSize: "25" });
    const result = await this.request(`users/me/dataTypes/exercise/dataPoints?${query}`);
    return result?.dataPoints?.find((point) =>
      Date.parse(point.exercise?.interval?.startTime) === session.startedAt &&
      Date.parse(point.exercise?.interval?.endTime) === session.endedAt) || null;
  }

  async sync() {
    if (this.syncing) return;
    this.syncing = true;
    const generation = this.generation;
    try {
      for (const session of await this.store.list()) {
        if (generation !== this.generation) break;
        if (session.synced || session.status !== "completed") continue;
        const payload = exportWorkout(session, await this.store.samples(session.id));
        let remote = await this.findWorkout(session);
        if (generation !== this.generation) break;
        if (!remote) {
          if (session.googleOperationPending) throw new Error("Google is still processing a workout. Retry sync later.");
          await this.store.update(session.id, { googlePending: true });
          if (generation !== this.generation) break;
          let operation;
          try {
            operation = await this.request("users/me/dataTypes/exercise/dataPoints", {
              method: "POST", body: JSON.stringify(payload),
            });
          } catch (error) {
            if (error.status === 409) {
              const existing = await this.findWorkout(session);
              if (existing) operation = { done: true, response: existing };
            }
            if (error.status && error.status !== 409) {
              await this.store.update(session.id, { googlePending: false });
            }
            if (!operation) throw new Error(`${error.message} The workout is retained locally; retry sync to check its remote status.`);
          }
          if (!operation) throw new Error("Google Health creation endpoint was not found.");
          if (operation.error) {
            await this.store.update(session.id, { googlePending: false });
            throw new Error(operation.error.message || "Google could not store the workout.");
          }
          if (!operation.done || !operation.response?.name) {
            await this.store.update(session.id, { googleOperationPending: true });
            throw new Error("Google is still processing a workout. Retry sync later.");
          }
          remote = operation.response;
        }
        if (generation !== this.generation) break;
        if (Date.parse(remote.exercise?.interval?.startTime) !== session.startedAt ||
          Date.parse(remote.exercise?.interval?.endTime) !== session.endedAt) {
          throw new Error("Google workout confirmation does not match the local session.");
        }
        await this.store.update(session.id, {
          synced: true, syncedAt: this.clock(), googleName: remote.name,
          googlePending: false, googleOperationPending: false,
        });
      }
    } finally {
      this.syncing = false;
    }
  }
}