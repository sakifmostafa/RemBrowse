// RemBrowse local network test page: tries to reach an address on the local network and one on
// this device, and says what happened, so the browser's Local Network Access question can be
// checked on a real device without developer tools.
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const logEl = $("log");
  const lines = [];
  const t0 = performance.now();
  // Long enough to answer the browser's question first, short enough to give up on an address
  // with nothing there
  const TIMEOUT_MS = 60000;

  function add(line) {
    lines.unshift(((performance.now() - t0) / 1000).toFixed(3) + "  " + line);
    if (lines.length > 200) lines.length = 200;
    logEl.textContent = lines.join("\n");
  }

  async function tryAddress(url) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    const started = performance.now();
    const ms = () => Math.round(performance.now() - started) + " ms";
    try {
      // no-cors: a device that sends no CORS headers still counts as reached (an opaque answer)
      const response = await fetch(url, { mode: "no-cors", cache: "no-store", signal: controller.signal });
      const status = response.type === "opaque" ? "an answer the page may not read" : "HTTP " + response.status;
      return { ok: true, text: "Reached, " + status + ", in " + ms() + "." };
    } catch (e) {
      const why = e && e.name === "AbortError" ? "nothing answered within " + TIMEOUT_MS / 1000 + " s" : (e && e.message) || String(e);
      return { ok: false, text: "Blocked or unreachable after " + ms() + ": " + why.replace(/\.$/, "") + "." };
    } finally {
      clearTimeout(timer);
    }
  }

  // ?lan=<address>&device=<address> fill in the boxes, and ?run=lan or ?run=device connects once
  // the page has loaded, for checks driven from a script
  const params = new URLSearchParams(location.search);

  for (const [key, formId, inputId, resultId] of [
    ["lan", "lan-form", "lan-url", "lan-result"],
    ["device", "device-form", "device-url", "device-result"],
  ]) {
    const form = $(formId);
    const input = $(inputId);
    const result = $(resultId);
    const button = form.querySelector("button");
    if (params.get(key)) input.value = params.get(key);
    if (params.get("run") === key) addEventListener("load", () => form.requestSubmit());
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const url = input.value.trim();
      button.disabled = true;
      result.textContent = "Connecting to " + url + "…";
      add(form.dataset.name + ": connecting to " + url);
      const outcome = await tryAddress(url);
      result.textContent = outcome.text;
      add(form.dataset.name + ": " + url + " " + (outcome.ok ? "REACHED" : "FAILED") + " " + outcome.text);
      button.disabled = false;
    });
  }

  $("clear").addEventListener("click", () => {
    lines.length = 0;
    logEl.textContent = "";
    $("lan-result").textContent = "";
    $("device-result").textContent = "";
  });
  $("copy").addEventListener("click", async () => {
    const all = "RemBrowse local network test\nuserAgent: " + navigator.userAgent + "\npage: " + location.href +
      "\n\nResults, oldest first:\n" + lines.slice().reverse().join("\n");
    try {
      await navigator.clipboard.writeText(all);
      $("copied").textContent = "Copied.";
    } catch (_) {
      const r = document.createRange();
      r.selectNodeContents(logEl);
      const s = getSelection();
      s.removeAllRanges();
      s.addRange(r);
      $("copied").textContent = "Selected: copy it with Ctrl+C.";
    }
  });
})();
