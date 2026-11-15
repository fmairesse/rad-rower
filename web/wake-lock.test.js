import assert from "node:assert/strict";
import test from "node:test";
import { SessionWakeLock } from "./wake-lock.js";

const flush = () => new Promise((resolve) => setImmediate(resolve));

function fixture() {
  const locks = [];
  const warnings = [];
  const wakeLock = { async request(type) {
    assert.equal(type, "screen");
    const lock = new EventTarget();
    lock.released = false;
    lock.release = async () => {
      lock.released = true;
      lock.dispatchEvent(new Event("release"));
    };
    locks.push(lock);
    return lock;
  } };
  return { locks, warnings, controller: new SessionWakeLock(wakeLock, (value) => warnings.push(value)) };
}

test("holds the screen awake only for an active, visible session", async () => {
  const { controller, locks } = fixture();
  controller.setActive(true);
  await flush();
  assert.equal(locks.length, 1);
  controller.setActive(false);
  await flush();
  assert.equal(locks[0].released, true);
  controller.setActive(true);
  await flush();
  controller.setVisible(false);
  await flush();
  assert.equal(locks[1].released, true);
  controller.setVisible(true);
  await flush();
  assert.equal(locks.length, 3);
  controller.setActive(false);
  await flush();
  assert.equal(locks[2].released, true);
});

test("releases a lock acquired after the session is paused", async () => {
  const { controller, locks } = fixture();
  controller.setActive(true);
  controller.setActive(false);
  await flush();
  assert.equal(locks[0].released, true);
});

test("retries after visibility returns while an obsolete request is being released", async () => {
  let resolveRelease;
  let requests = 0;
  const firstLock = new EventTarget();
  firstLock.release = () => new Promise((resolve) => { resolveRelease = resolve; });
  const secondLock = new EventTarget();
  secondLock.release = async () => {};
  const controller = new SessionWakeLock({ request: async () => {
    requests += 1;
    return requests === 1 ? firstLock : secondLock;
  } }, () => {});
  controller.setActive(true);
  controller.setVisible(false);
  await flush();
  controller.setVisible(true);
  resolveRelease();
  await flush();
  assert.equal(requests, 2);
  assert.equal(controller.lock, secondLock);
});

test("reports unsupported, rejected and externally released locks", async () => {
  const warnings = [];
  const unsupported = new SessionWakeLock(null, (value) => warnings.push(value));
  unsupported.setActive(true);
  assert.match(warnings.at(-1), /unavailable/);
  unsupported.setActive(false);
  assert.equal(warnings.at(-1), "");
  const rejected = new SessionWakeLock({ request: async () => { throw new Error("denied"); } },
    (value) => warnings.push(value));
  rejected.setActive(true);
  await flush();
  assert.match(warnings.at(-1), /could not be enabled/);
  const { controller, locks, warnings: releaseWarnings } = fixture();
  controller.setActive(true);
  await flush();
  await locks[0].release();
  assert.match(releaseWarnings.at(-1), /was released/);
});