import { RowerConnection } from "./rower.js";

if (globalThis.isSecureContext && "serviceWorker" in navigator) {
  navigator.serviceWorker.register("./service-worker.js").catch((error) => {
    console.warn("Radrower offline support could not be enabled.", error);
  });
}

if (!globalThis.Vue) {
  document.querySelector("#startup").textContent =
    "Vue.js could not be loaded. Check access to cdn.jsdelivr.net and reload the page.";
} else {
  const { createApp, ref, computed, onMounted, onUnmounted } = globalThis.Vue;
  createApp({
    setup() {
      const state = ref("disconnected");
      const busy = ref(false);
      const controlling = ref(false);
      const error = ref("");
      const warning = ref("");
      const journal = ref([]);
      const metrics = ref({});
      const updated = ref({});
      const now = ref(Date.now());
      const hidden = ref(document.hidden);
      const installPrompt = ref(null);
      const installed = ref(globalThis.matchMedia?.("(display-mode: standalone)").matches ||
        navigator.standalone === true);
      const supported = globalThis.isSecureContext && Boolean(navigator.bluetooth);
      const supportError = !globalThis.isSecureContext
        ? "Web Bluetooth requires HTTPS or localhost. An HTTP address on your local network is not sufficient."
        : "Web Bluetooth is not available here. Try a compatible version of Chrome or Edge (not Firefox or Safari).";
      const labels = {
        disconnected: "Disconnected",
        connecting: "Connecting",
        initializing: "Initializing",
        streaming: "Connected",
      };
      const log = (text) => {
        journal.value.unshift({ time: new Date().toLocaleTimeString("en-US"), text });
        journal.value = journal.value.slice(0, 40);
      };
      const connection = new RowerConnection({
        bluetooth: navigator.bluetooth,
        onState(value) {
          state.value = value;
          log(labels[value]);
        },
        onMetrics(values) {
          const timestamp = Date.now();
          metrics.value = { ...metrics.value, ...values };
          for (const key of Object.keys(values)) updated.value[key] = timestamp;
          now.value = timestamp;
        },
        onWarning(message) {
          warning.value = message;
          log(message);
        },
        onError(cause) {
          error.value = cause.name === "NotFoundError"
            ? "Selection cancelled or rower not found. Turn it on and try again."
            : `Connection interrupted: ${cause.message || String(cause)}`;
          log(error.value);
        },
      });
      const isStale = (key) => updated.value[key] &&
        (state.value !== "streaming" || now.value - updated.value[key] > 5000);
      const definitions = [
        ["watts", "Power", "W"],
        ["pace", "Pace / 500 m", "/ 500 m"],
        ["averagePace", "Average pace", "/ 500 m"],
        ["cadence", "Cadence", "strokes/min"],
        ["distance", "Distance", "m"],
        ["strokes", "Stroke count", "strokes"],
        ["speed", "Speed", "km/h"],
        ["resistance", "Resistance", "/ 24"],
        ["elapsed", "Elapsed time", ""],
        ["paused", "Paused time", ""],
        ["calories", "Calories", "kcal"],
        ["caloriesPerHour", "Estimated burn", "kcal/h"],
        ["averageWatts", "Average power", "W"],
      ];
      const number = new Intl.NumberFormat("en-US", { maximumFractionDigits: 2 });
      const cards = computed(() => definitions.map(([key, label, unit]) => {
        const value = metrics.value[key];
        const received = Boolean(updated.value[key]);
        let display = value == null ? "—" : typeof value === "number" ? number.format(value) : value;
        if (["pace", "averagePace", "elapsed", "paused"].includes(key) && value != null) {
          display = `${Math.floor(value / 60)}:${String(value % 60).padStart(2, "0")}`;
        }
        return {
          key, label, unit, value: display, stale: Boolean(isStale(key)),
          note: !received ? "Waiting for data" : isStale(key) ? "Last value · not up to date" :
            key === "resistance" && value == null ? "Level not reported by the rower" :
            key === "speed" ? "Calculated from pace" :
            key === "caloriesPerHour" ? "Estimated from power" :
            ["paused", "averagePace", "averageWatts"].includes(key) ? "Likely interpretation, not yet confirmed" :
            value == null ? "Value unavailable" : "Data from the rower",
        };
      }));
      const freshnessLabel = computed(() => {
        if (state.value === "disconnected") {
          return Object.keys(updated.value).length
            ? "Disconnected · last values are retained for reference."
            : "Metrics will appear after connecting.";
        }
        if (state.value !== "streaming") return "Preparing to read metrics…";
        const required = ["watts", "cadence"];
        if (required.some((key) => !updated.value[key])) {
          return "Waiting for both groups of rower metrics…";
        }
        if (required.some(isStale)) return "Warning: some metrics are no longer up to date.";
        return "Receiving live data · polling every 0.6 s.";
      });
      async function connect() {
        error.value = "";
        warning.value = "";
        metrics.value = {};
        updated.value = {};
        busy.value = true;
        try {
          await connection.connect();
        } catch (cause) {
          error.value = `Connection failed: ${cause.message || String(cause)}`;
          log(error.value);
        } finally {
          busy.value = false;
        }
      }
      function disconnect() {
        connection.disconnect();
      }
      const sessionState = computed(() => metrics.value.sessionState);
      const sessionReady = computed(() => state.value === "streaming" &&
        !controlling.value && Boolean(updated.value.sessionState) && !isStale("sessionState"));
      const sessionLabel = computed(() => ({
        1: "Idle", 2: "In progress", 3: "Paused", 4: "Finished",
      }[sessionState.value] || "State unavailable"));
      async function control(action) {
        error.value = "";
        controlling.value = true;
        try {
          await connection.control(action);
          log(action === "stop" ? "Session stopped." : "Session state confirmed.");
        } catch (cause) {
          error.value = `Session command: ${cause.message || String(cause)}`;
          log(error.value);
        } finally {
          controlling.value = false;
        }
      }
      const visibilityChanged = () => { hidden.value = document.hidden; };
      const beforeInstall = (event) => {
        event.preventDefault();
        installPrompt.value = event;
      };
      const appInstalled = () => {
        installed.value = true;
        installPrompt.value = null;
      };
      async function installApp() {
        const prompt = installPrompt.value;
        if (!prompt) return;
        try {
          await prompt.prompt();
          await prompt.userChoice;
        } finally {
          installPrompt.value = null;
        }
      }
      let ticker;
      onMounted(() => {
        document.querySelector("#startup").remove();
        ticker = setInterval(() => { now.value = Date.now(); }, 1000);
        document.addEventListener("visibilitychange", visibilityChanged);
        globalThis.addEventListener("pagehide", disconnect);
        globalThis.addEventListener("beforeinstallprompt", beforeInstall);
        globalThis.addEventListener("appinstalled", appInstalled);
      });
      onUnmounted(() => {
        clearInterval(ticker);
        document.removeEventListener("visibilitychange", visibilityChanged);
        globalThis.removeEventListener("pagehide", disconnect);
        globalThis.removeEventListener("beforeinstallprompt", beforeInstall);
        globalThis.removeEventListener("appinstalled", appInstalled);
        disconnect();
      });
      return {
        state, stateLabel: computed(() => labels[state.value]), busy,
        canInstall: computed(() => Boolean(installPrompt.value) && !installed.value), installApp,
        supported, supportError, error, warning, journal, cards, hidden, freshnessLabel,
        sessionState, sessionReady, sessionLabel, controlling, control, connect, disconnect,
        connectionHint: computed(() => state.value === "streaming"
          ? "I_RW · Metrics and session"
          : state.value === "initializing" ? "Initializing the protocol…"
          : state.value === "connecting" ? "Selecting and connecting via Bluetooth…"
          : "Turn on the rower and free its Bluetooth connection."),
      };
    },
  }).mount("#app");
}
