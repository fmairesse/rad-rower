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
  recorder.record({ sessionState: 1, distance: 999 });
  recorder.record({ sessionState: 2, elapsed: 0, distance: 0 });
  recorder.record({ watts: 120, cadence: 24 });
  recorder.record({ sessionState: 3, distance: 100, calories: 10 });
  recorder.record({ watts: null });
  recorder.record({ sessionState: 2, elapsed: 20 });
  recorder.record({ sessionState: 1, distance: 0, calories: 0 });
  await recorder.finish();
  assert.equal(sessions.size, 1);
  const session = [...sessions.values()][0];
  assert.equal(session.status, "completed");
  assert.equal(session.synced, false);
  assert.equal(session.partial, false);
  assert.equal(session.metrics.distance, 100);
  assert.equal(session.metrics.calories, 10);
  assert.equal(samples.length, session.sampleCount);
  assert.deepEqual(samples[1].metrics, { watts: 120, cadence: 24 });
  assert.equal(samples[3].metrics.watts, null);
});

test("disconnect preserves interrupted sessions, recovery handles a closed tab", async () => {
  const { recorder, sessions } = fixture();
  recorder.record({ sessionState: 3, elapsed: 50 });
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

test("the final end-state frame contributes its latest totals", async () => {
  const { recorder, sessions } = fixture();
  recorder.record({ sessionState: 2, distance: 100 });
  recorder.record({ sessionState: 4, distance: 105, calories: 12 });
  recorder.record({ sessionState: 1, distance: 0 });
  await recorder.pending;
  assert.equal([...sessions.values()][0].metrics.distance, 105);
  assert.equal([...sessions.values()][0].metrics.calories, 12);
});