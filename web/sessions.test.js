import test from "node:test";
import assert from "node:assert/strict";
import { SessionRecorder } from "./sessions.js";

function fixture() {
  const sessions = new Map();
  const samples = [];
  const store = {
    async save(session, sample) {
      sessions.set(session.id, structuredClone(session));
      if (sample) samples.push(sample);
    },
    async list() { return [...sessions.values()]; },
    async samples(id) { return samples.filter((sample) => sample.sessionId === id); },
  };
  let timestamp = 1000;
  const errors = [];
  const recorder = new SessionRecorder(store, {
    clock: () => timestamp += 1000, makeId: () => `session-${sessions.size}`,
    onError: (error) => errors.push(error),
  });
  return { recorder, sessions, samples, store, errors };
}

test("records metric deltas, pauses and resumes; finalizes once without idle counter reset", async () => {
  const { recorder, sessions, samples } = fixture();
  recorder.record({ sessionState: 1, distanceM: 999 });
  recorder.record({ sessionState: 2, elapsedS: 0, distanceM: 0 });
  recorder.record({ powerW: 120, cadenceSpm: 24 });
  recorder.record({ sessionState: 3, distanceM: 100, caloriesKcal: 10 });
  recorder.record({ powerW: null });
  recorder.record({ sessionState: 2, elapsedS: 20 });
  recorder.record({ sessionState: 1, distanceM: 0, caloriesKcal: 0 });
  await recorder.finish();
  assert.equal(sessions.size, 1);
  const session = [...sessions.values()][0];
  assert.equal(session.status, "completed");
  assert.equal(session.synced, false);
  assert.equal(session.partial, false);
  assert.equal(session.metrics.distanceM, 100);
  assert.equal(session.metrics.caloriesKcal, 10);
  assert.equal(session.metrics.averageCadenceSpm, 24);
  assert.equal("cadence" in session.metrics, false);
  assert.equal("watts" in session.metrics, false);
  assert.equal(samples.length, session.sampleCount);
  assert.deepEqual(samples[1].metrics, { powerW: 120, cadenceSpm: 24 });
  assert.equal(samples[3].metrics.powerW, null);
});

test("summarizes active-session averages and retains unverified sample fields", async () => {
  const { recorder, sessions, samples } = fixture();
  recorder.record({ sessionState: 2, elapsedS: 0, distanceM: 0 });
  recorder.record({ cadenceSpm: 20, paceSPer500M: 150, speedKmh: 12, caloriesPerHour: 900, averagePace: 120 });
  recorder.record({ cadenceSpm: 40, paceSPer500M: 140, speedKmh: 13, powerW: 180 });
  recorder.record({ sessionState: 4, elapsedS: 10, paused: 0, distanceM: 100,
    caloriesKcal: 15, averageWatts: 150 });
  await recorder.pending;

  const metrics = [...sessions.values()][0].metrics;
  assert.equal(metrics.averageCadenceSpm, 30);
  assert.equal(metrics.averageSpeedKmh, 120);
  assert.equal(metrics.averagePaceSPer500M, 15);
  assert.equal(metrics.averageWatts, 150);
  assert.equal("pace" in metrics, false);
  assert.equal("speed" in metrics, false);
  assert.equal("caloriesPerHour" in metrics, false);
  assert.equal(samples[1].metrics.caloriesPerHour, 900);
  assert.equal(samples[1].metrics.averagePace, 120);
  assert.equal(samples[1].metrics.paceSPer500M, 150);
});

test("disconnect preserves interrupted sessions, recovery handles a closed tab", async () => {
  const { recorder, sessions } = fixture();
  recorder.record({ sessionState: 3, elapsedS: 50 });
  await recorder.finish("interrupted");
  assert.equal([...sessions.values()][0].status, "interrupted");
  assert.equal([...sessions.values()][0].partial, true);
  recorder.record({ sessionState: 2 });
  await recorder.pending;
  await recorder.recover();
  assert.equal([...sessions.values()][1].status, "interrupted");
});

test("storage failure is surfaced and later writes can retry", async () => {
  const { recorder, store, errors, sessions } = fixture();
  const save = store.save;
  store.save = async () => { throw new Error("Quota exceeded"); };
  recorder.record({ sessionState: 2 });
  await recorder.pending;
  assert.equal(errors.length, 1);
  store.save = save;
  await recorder.finish();
  assert.equal([...sessions.values()][0].status, "completed");
});

test("terminal counter resets do not erase the latest session totals", async () => {
  const { recorder, sessions } = fixture();
  recorder.record({ sessionState: 2, distanceM: 105, caloriesKcal: 12, averageWatts: 228 });
  recorder.record({ sessionState: 4, distanceM: 0, caloriesKcal: 0, averageWatts: 0 });
  recorder.record({ sessionState: 1, distanceM: 0, caloriesKcal: 0 });
  await recorder.pending;
  assert.equal([...sessions.values()][0].metrics.distanceM, 105);
  assert.equal([...sessions.values()][0].metrics.caloriesKcal, 12);
  assert.equal([...sessions.values()][0].metrics.averageWatts, 228);
});