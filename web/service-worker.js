const CACHE_NAME = "radrower-shell-v6";
const APP_SHELL = [
  "./index.html",
  "./styles.css",
  "./app.js",
  "./wake-lock.js",
  "./sessions.js",
  "./google-health.js",
  "./intervals-icu.js",
  "./rower.js",
  "./protocol.js",
  "./manifest.webmanifest",
  "./icon.svg",
];
const VUE_SCRIPT = "https://cdn.jsdelivr.net/npm/vue@3.5.13/dist/vue.global.prod.js";

self.addEventListener("install", (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await cache.addAll(APP_SHELL);
    try {
      const request = new Request(VUE_SCRIPT, { mode: "no-cors" });
      const response = await fetch(request);
      await cache.put(request, response);
    } catch {
      // The app shell can still be installed if the CDN is unavailable.
    }
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    const cacheNames = await caches.keys();
    await Promise.all(cacheNames
      .filter((name) => name.startsWith("radrower-") && name !== CACHE_NAME)
      .map((name) => caches.delete(name)));
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin === self.location.origin &&
      !request.mode.includes("navigate") && !APP_SHELL.some((path) =>
        new URL(path, self.registration.scope).pathname === url.pathname)) return;
  if (url.origin !== self.location.origin && request.url !== VUE_SCRIPT) return;

  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    try {
      const response = await fetch(request);
      if (response.ok || response.type === "opaque") {
        await cache.put(request, response.clone());
      }
      return response;
    } catch {
      const cached = await cache.match(request);
      if (cached) return cached;
      if (request.mode === "navigate") {
        return cache.match(new URL("./index.html", self.registration.scope));
      }
      return Response.error();
    }
  })());
});
