import { RowerConnection } from "./rower.js";

if (!globalThis.Vue) {
  document.querySelector("#startup").textContent =
    "Vue.js n’a pas pu être chargé. Vérifiez l’accès à cdn.jsdelivr.net puis rechargez la page.";
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
      const supported = globalThis.isSecureContext && Boolean(navigator.bluetooth);
      const supportError = !globalThis.isSecureContext
        ? "Web Bluetooth exige HTTPS ou localhost. Une adresse HTTP du réseau local ne suffit pas."
        : "Web Bluetooth n’est pas disponible ici. Essayez Chrome ou Edge compatible (pas Firefox ou Safari).";
      const labels = {
        disconnected: "Déconnecté",
        connecting: "Connexion",
        initializing: "Initialisation",
        streaming: "Connecté",
      };
      const log = (text) => {
        journal.value.unshift({ time: new Date().toLocaleTimeString("fr-FR"), text });
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
            ? "Sélection annulée ou rameur introuvable. Allumez-le et réessayez."
            : `Connexion interrompue : ${cause.message || String(cause)}`;
          log(error.value);
        },
      });
      const isStale = (key) => updated.value[key] &&
        (state.value !== "streaming" || now.value - updated.value[key] > 5000);
      const definitions = [
        ["watts", "Puissance", "W"],
        ["pace", "Allure / 500 m", "/ 500 m"],
        ["averagePace", "Allure moyenne", "/ 500 m"],
        ["cadence", "Cadence", "coups/min"],
        ["distance", "Distance", "m"],
        ["strokes", "Nombre de coups", "coups"],
        ["speed", "Vitesse", "km/h"],
        ["resistance", "Résistance", "/ 24"],
        ["elapsed", "Temps écoulé", ""],
        ["paused", "Temps en pause", ""],
        ["calories", "Calories", "kcal"],
        ["caloriesPerHour", "Dépense estimée", "kcal/h"],
        ["averageWatts", "Puissance moyenne", "W"],
      ];
      const number = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 2 });
      const cards = computed(() => definitions.map(([key, label, unit]) => {
        const value = metrics.value[key];
        const received = Boolean(updated.value[key]);
        let display = value == null ? "—" : typeof value === "number" ? number.format(value) : value;
        if (["pace", "averagePace", "elapsed", "paused"].includes(key) && value != null) {
          display = `${Math.floor(value / 60)}:${String(value % 60).padStart(2, "0")}`;
        }
        return {
          key, label, unit, value: display, stale: Boolean(isStale(key)),
          note: !received ? "En attente de données" : isStale(key) ? "Dernière valeur · non actualisée" :
            key === "resistance" && value == null ? "Niveau non transmis par le rameur" :
            key === "speed" ? "Calculée à partir de l’allure" :
            key === "caloriesPerHour" ? "Estimation à partir de la puissance" :
            ["paused", "averagePace", "averageWatts"].includes(key) ? "Interprétation probable, à confirmer" :
            value == null ? "Valeur indisponible" : "Donnée du rameur",
        };
      }));
      const freshnessLabel = computed(() => {
        if (state.value === "disconnected") {
          return Object.keys(updated.value).length
            ? "Déconnecté · les dernières valeurs sont conservées à titre indicatif."
            : "Les métriques apparaîtront après la connexion.";
        }
        if (state.value !== "streaming") return "Préparation de la lecture des métriques…";
        const required = ["watts", "cadence"];
        if (required.some((key) => !updated.value[key])) {
          return "En attente des deux groupes de métriques du rameur…";
        }
        if (required.some(isStale)) return "Attention : certaines métriques ne sont plus actualisées.";
        return "Réception en direct · interrogation toutes les 0,6 s.";
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
          error.value = `Connexion impossible : ${cause.message || String(cause)}`;
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
        1: "Au repos", 2: "En cours", 3: "En pause", 4: "Terminée",
      }[sessionState.value] || "État indisponible"));
      async function control(action) {
        error.value = "";
        controlling.value = true;
        try {
          await connection.control(action);
          log(action === "stop" ? "Séance arrêtée." : "État de la séance confirmé.");
        } catch (cause) {
          error.value = `Commande de séance : ${cause.message || String(cause)}`;
          log(error.value);
        } finally {
          controlling.value = false;
        }
      }
      const visibilityChanged = () => { hidden.value = document.hidden; };
      let ticker;
      onMounted(() => {
        document.querySelector("#startup").remove();
        ticker = setInterval(() => { now.value = Date.now(); }, 1000);
        document.addEventListener("visibilitychange", visibilityChanged);
        globalThis.addEventListener("pagehide", disconnect);
      });
      onUnmounted(() => {
        clearInterval(ticker);
        document.removeEventListener("visibilitychange", visibilityChanged);
        globalThis.removeEventListener("pagehide", disconnect);
        disconnect();
      });
      return {
        state, stateLabel: computed(() => labels[state.value]), busy,
        supported, supportError, error, warning, journal, cards, hidden, freshnessLabel,
        sessionState, sessionReady, sessionLabel, controlling, control, connect, disconnect,
        connectionHint: computed(() => state.value === "streaming"
          ? "I_RW · Métriques et séance"
          : state.value === "initializing" ? "Initialisation du protocole en cours…"
          : state.value === "connecting" ? "Sélection et connexion Bluetooth…"
          : "Allumez le rameur et libérez sa connexion Bluetooth."),
      };
    },
  }).mount("#app");
}
