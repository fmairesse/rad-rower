export class SessionWakeLock {
  constructor(wakeLock, onWarning) {
    this.wakeLock = wakeLock;
    this.onWarning = onWarning;
    this.active = false;
    this.visible = true;
    this.lock = null;
    this.pending = false;
    this.revision = 0;
  }

  setActive(active) {
    if (this.active === active) return;
    this.active = active;
    this.revision += 1;
    this.update();
  }

  setVisible(visible) {
    if (this.visible === visible) return;
    this.visible = visible;
    this.revision += 1;
    this.update();
  }

  async update() {
    if (!this.active || !this.visible) {
      this.onWarning("");
      const lock = this.lock;
      this.lock = null;
      if (lock) await lock.release();
      return;
    }
    if (this.lock || this.pending) return;
    if (!this.wakeLock) {
      this.onWarning("Screen wake lock is unavailable. Keep the screen awake manually.");
      return;
    }
    this.pending = true;
    const revision = this.revision;
    try {
      const lock = await this.wakeLock.request("screen");
      if (!this.active || !this.visible) {
        await lock.release();
        return;
      }
      this.lock = lock;
      lock.addEventListener("release", () => {
        if (this.lock !== lock) return;
        this.lock = null;
        if (this.active && this.visible) {
          this.onWarning("Screen wake lock was released. Keep the screen awake manually.");
        }
      });
      this.onWarning("");
    } catch {
      if (this.active && this.visible) {
        this.onWarning("Screen wake lock could not be enabled. Keep the screen awake manually.");
      }
    } finally {
      this.pending = false;
      if (revision !== this.revision && this.active && this.visible && !this.lock) {
        this.update();
      }
    }
  }
}