import test from "node:test";
import assert from "node:assert/strict";
import { exportWorkout, GoogleHealth, HEALTH_SCOPE } from "./google-health.js";

const session = {
  id: "a1b2c3d4-e5f6-7890-1234-567890abcdef", status: "completed", synced: false,
  startedAt: 100000, endedAt: 200000, partial: false,
  metrics: { distanceM: 500, caloriesKcal: 20, powerW: 150, strokesCount: 30, cadenceSpm: 24 },
};
const samples = [
  { timestamp: 100000, metrics: { sessionState: 2, powerW: 100 } },
  { timestamp: 130000, metrics: { sessionState: 3, powerW: null } },
  { timestamp: 150000, metrics: { sessionState: 2, powerW: 200 } },
];

test("exports rowing totals in Google units, pause events and extra metrics without inventing steps", () => {
  const { exercise } = exportWorkout(session, samples);
  assert.equal(exercise.exerciseType, "ROWING_MACHINE");
  assert.equal(exercise.activeDuration, "80s");
  assert.deepEqual(exercise.metricsSummary, { distanceMillimeters: 500000,
    averageSpeedMillimetersPerSecond: 6250, averagePaceSecondsPerMeter: 0.16, caloriesKcal: 20 });
  assert.deepEqual(exercise.exerciseEvents.map((event) => event.exerciseEventType), ["START", "PAUSE", "RESUME", "STOP"]);
  assert.equal(JSON.parse(exercise.notes).rowerMetrics.strokesCount, 30);
  assert.equal(JSON.parse(exercise.notes).sampledStatistics.powerW.sampleMean, 150);
  assert.throws(() => exportWorkout({ ...session, status: "interrupted" }, samples));
});

function fixture(responses) {
  const saved = structuredClone(session);
  const calls = [];
  const store = {
    async list() { return [structuredClone(saved)]; },
    async samples() { return samples; },
    async update(id, changes) { Object.assign(saved, changes); },
  };
  const health = new GoogleHealth(store, { fetch: async (url, options) => {
    calls.push({ url, options });
    const response = responses.shift();
    if (response instanceof Error) throw response;
    return new Response(JSON.stringify(response.body), { status: response.status ?? 200 });
  } });
  health.token = "test-token";
  health.expiresAt = Infinity;
  return { health, saved, calls };
}

const remote = {
  name: "users/me/dataTypes/exercise/dataPoints/3054085373369111216",
  exercise: exportWorkout(session, samples).exercise,
};

test("marks synced only after a confirmed operation and never uploads again", async () => {
  const { health, saved, calls } = fixture([
    { body: { dataPoints: [] } }, { body: { done: true, response: remote } },
  ]);
  await health.sync();
  assert.equal(saved.synced, true);
  assert.equal(new URL(calls[0].url).searchParams.has("filter"), true);
  assert.equal(calls[0].options.method, undefined);
  assert.equal(calls[1].options.method, "POST");
  assert.equal("name" in JSON.parse(calls[1].options.body), false);
  await health.sync();
  assert.equal(calls.length, 2);
});

test("uncertain writes are checked remotely on retry instead of duplicated", async () => {
  const { health, saved, calls } = fixture([
    { body: { dataPoints: [] } }, new Error("Network lost"),
    { body: { dataPoints: [remote] } },
  ]);
  await assert.rejects(health.sync(), /retained locally/);
  assert.equal(saved.synced, false);
  assert.equal(saved.googlePending, true);
  await health.sync();
  assert.equal(saved.synced, true);
  assert.equal(calls.filter((call) => call.options.method === "POST").length, 1);
});

test("pending operations, rejected operations and expired tokens keep sessions unsynced", async () => {
  for (const response of [{ done: false }, { done: true, error: { message: "Invalid workout" } }]) {
    const { health, saved } = fixture([{ body: { dataPoints: [] } }, { body: response }]);
    await assert.rejects(health.sync());
    assert.equal(saved.synced, false);
  }
  const { health, saved } = fixture([{ status: 401, body: {} }]);
  await assert.rejects(health.sync(), /expired/);
  assert.equal(health.connected, false);
  assert.equal(saved.synced, false);
});

test("Google token flow requests only write consent, handles popup failure and disconnect", async () => {
  let config;
  const health = new GoogleHealth({}, { oauth: () => ({
    initTokenClient(value) {
      config = value;
      return { requestAccessToken() { value.callback({ access_token: "test", expires_in: 3600 }); } };
    },
    hasGrantedAllScopes: () => true,
  }) });
  await health.connect();
  assert.equal(config.scope, HEALTH_SCOPE);
  assert.equal(health.connected, true);
  health.disconnect();
  assert.equal(health.connected, false);
  health.oauth = () => ({ initTokenClient: (value) => ({ requestAccessToken: () => value.error_callback() }) });
  await assert.rejects(health.connect(), /closed or blocked/);
});

test("explicit HTTP rejection allows retry and equivalent timestamp formatting confirms success", async () => {
  const alternate = structuredClone(remote);
  alternate.exercise.interval.startTime = alternate.exercise.interval.startTime.replace(".000Z", "Z");
  alternate.exercise.interval.endTime = alternate.exercise.interval.endTime.replace(".000Z", "Z");
  const { health, saved } = fixture([
    { body: { dataPoints: [] } }, { status: 403, body: { error: { message: "Access denied" } } },
    { body: { dataPoints: [] } }, { body: { done: true, response: alternate } },
  ]);
  await assert.rejects(health.sync(), /Access denied/);
  assert.equal(saved.googlePending, false);
  await health.sync();
  assert.equal(saved.synced, true);
});

test("a lost write and create conflict are resolved by querying the exercise collection", async () => {
  const { health, saved, calls } = fixture([
    { body: { dataPoints: [] } }, new Error("Network lost"),
    { body: { dataPoints: [] } }, { status: 409, body: { error: { message: "Already exists" } } },
    { body: { dataPoints: [remote] } },
  ]);
  await assert.rejects(health.sync());
  await health.sync();
  const writes = calls.filter((call) => call.options.method === "POST");
  assert.equal(writes.length, 2);
  assert.equal("name" in JSON.parse(writes[0].options.body), false);
  assert.equal("name" in JSON.parse(writes[1].options.body), false);
  assert.equal(saved.synced, true);
});