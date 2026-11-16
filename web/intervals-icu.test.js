import test from "node:test";
import assert from "node:assert/strict";
import { exportWorkout, IntervalsIcu } from "./intervals-icu.js";

const session = {
  id: "a1b2c3d4-e5f6-7890-1234-567890abcdef", status: "completed", synced: false,
  startedAt: Date.parse("2026-11-16T10:00:00Z"), endedAt: Date.parse("2026-11-16T10:02:00Z"),
  partial: false,
  metrics: { distance: 500, calories: 20, averageWatts: 150, averageCadence: 24, strokes: 48 },
};
const samples = [
  { timestamp: session.startedAt, metrics: { sessionState: 2, watts: 100 } },
  { timestamp: session.startedAt + 30000, metrics: { sessionState: 3 } },
  { timestamp: session.startedAt + 50000, metrics: { sessionState: 2, watts: 200 } },
];

function fixture(responses) {
  const saved = structuredClone(session);
  const calls = [];
  const store = {
    async list() { return [structuredClone(saved)]; },
    async samples() { return samples; },
    async update(id, changes) { Object.assign(saved, changes); },
  };
  const intervals = new IntervalsIcu(store, { fetch: async (url, options) => {
    calls.push({ url, options });
    const response = responses.shift();
    if (response instanceof Error) throw response;
    return new Response(JSON.stringify(response.body), { status: response.status ?? 200 });
  } });
  return { intervals, saved, calls };
}

test("exports a completed indoor rowing activity with its local timestamps and pauses", () => {
  const payload = exportWorkout(session, samples);
  assert.equal(payload.type, "Rowing");
  assert.equal(payload.elapsed_time, 120);
  assert.equal(payload.moving_time, 100);
  assert.equal(payload.distance, 500);
  assert.equal(payload.average_watts, 150);
  assert.equal(payload.average_cadence, 24);
  assert.equal(payload.external_id, `radrower-${session.id}`);
  assert.equal(JSON.parse(payload.description).rowerMetrics.strokes, 48);
  assert.throws(() => exportWorkout({ ...session, status: "interrupted" }, samples));
});

test("connects with HTTP Basic and keeps API key only in the client instance", async () => {
  const { intervals, calls } = fixture([{ body: { id: "athlete-123" } }]);
  await intervals.connect("private-key");
  assert.equal(intervals.connected, true);
  assert.equal(new Headers(calls[0].options.headers).get("Authorization"),
    `Basic ${btoa("API_KEY:private-key")}`);
  intervals.disconnect();
  assert.equal(intervals.connected, false);
});

test("syncs only completed sessions and marks success after the remote activity is found", async () => {
  const { intervals, saved, calls } = fixture([
    { body: { id: "athlete-123" } }, { body: [] },
    { body: { id: "activity-1", external_id: `radrower-${session.id}` } },
  ]);
  await intervals.connect("private-key");
  await intervals.sync();
  assert.equal(saved.intervalsSynced, true);
  assert.equal(saved.intervalsActivityId, "activity-1");
  assert.equal(calls[2].options.method, "POST");
  assert.equal(new URL(calls[2].url).pathname.endsWith("/activities/manual"), true);
  await intervals.sync();
  assert.equal(calls.length, 3);
});

test("a lost creation response is reconciled before any retry", async () => {
  const activity = { id: "activity-1", external_id: `radrower-${session.id}` };
  const { intervals, saved, calls } = fixture([
    { body: { id: "athlete-123" } }, { body: [] }, new Error("Network lost"),
    { body: [activity] },
  ]);
  await intervals.connect("private-key");
  await intervals.sync();
  assert.equal(saved.intervalsSynced, true);
  assert.equal(calls.filter((call) => call.options.method === "POST").length, 1);
});

test("rejects an invalid key and leaves the client disconnected", async () => {
  const { intervals } = fixture([{ status: 401, body: {} }]);
  await assert.rejects(intervals.connect("wrong-key"), /rejected the API key/);
  assert.equal(intervals.connected, false);
});