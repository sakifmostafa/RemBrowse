// RemBrowse site: the split-view demo, the start-page clock, and download links filled in from
// update.json (the same file the app's own update check reads), so the page always offers the
// newest version without being edited.
(() => {
  "use strict";

  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  // ---------- Split-view demo ----------
  const demo = document.querySelector("[data-demo]");
  if (demo) {
    const win = demo.querySelector(".win");
    const buttons = Array.from(demo.querySelectorAll("[data-layout-btn]"));
    const pauseBtn = demo.querySelector("[data-demo-pause]");
    const status = demo.querySelector("[data-demo-status]");
    const order = buttons.map((b) => b.dataset.layoutBtn);
    const descriptions = {
      side: "two pages side by side: an article and a terminal",
      stacked: "two pages stacked: an article above a terminal",
      grid: "four pages in a grid: an article, a terminal, a video and the start page",
      onetwo: "one page on the left and two on the right: an article, a terminal and a video",
    };
    const names = { side: "Side by side", stacked: "Stacked", grid: "Grid", onetwo: "One and two" };
    let index = Math.max(0, order.indexOf(win.dataset.layout));
    let playing = !reduceMotion.matches;
    let onScreen = true;
    let held = false; // pointer or keyboard focus on the demo
    let timer = null;

    const setLayout = (name, announce) => {
      win.dataset.layout = name;
      index = order.indexOf(name);
      for (const b of buttons) b.setAttribute("aria-pressed", String(b.dataset.layoutBtn === name));
      win.setAttribute("aria-label", "A RemBrowse window with " + descriptions[name] + ".");
      if (announce && status) status.textContent = names[name] + ": " + descriptions[name] + ".";
    };

    const sync = () => {
      clearInterval(timer);
      timer = null;
      if (playing && onScreen && !held && !document.hidden) {
        timer = setInterval(() => setLayout(order[(index + 1) % order.length], false), 3400);
      }
      if (pauseBtn) {
        pauseBtn.hidden = reduceMotion.matches;
        pauseBtn.setAttribute("aria-label", playing ? "Pause the layout demo" : "Play the layout demo");
        pauseBtn.querySelector("use").setAttribute("href", playing ? "#i-pause" : "#i-play");
      }
    };

    for (const b of buttons) {
      b.addEventListener("click", () => {
        playing = false;
        setLayout(b.dataset.layoutBtn, true);
        sync();
      });
    }
    if (pauseBtn) {
      pauseBtn.addEventListener("click", () => {
        playing = !playing;
        sync();
      });
    }
    if ("IntersectionObserver" in window) {
      new IntersectionObserver((entries) => {
        onScreen = entries[entries.length - 1].isIntersecting;
        sync();
      }).observe(win);
    }
    document.addEventListener("visibilitychange", sync);
    const hold = (on) => () => {
      held = on;
      sync();
    };
    demo.addEventListener("pointerenter", hold(true));
    demo.addEventListener("pointerleave", hold(false));
    demo.addEventListener("focusin", hold(true));
    demo.addEventListener("focusout", (e) => {
      if (!demo.contains(e.relatedTarget)) hold(false)();
    });
    const onMotionChange = () => {
      if (reduceMotion.matches) playing = false;
      sync();
    };
    if (reduceMotion.addEventListener) reduceMotion.addEventListener("change", onMotionChange);
    sync();
  }

  // ---------- Start-page clock (like the app's own new tab) ----------
  const clock = document.querySelector("[data-clock]");
  const clockDate = document.querySelector("[data-clock-date]");
  if (clock) {
    const tick = () => {
      const now = new Date();
      clock.textContent = now.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }).replace(/\s?[AP]M$/i, "");
      if (clockDate) clockDate.textContent = now.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" });
    };
    tick();
    setInterval(tick, 15000);
  }

  // ---------- Downloads ----------
  const cards = Array.from(document.querySelectorAll("[data-abi]"));
  const heroLink = document.querySelector("[data-hero-download]");
  const heroLabel = document.querySelector("[data-hero-label]");
  const heroSub = document.querySelector("[data-hero-sub]");
  const statusLine = document.querySelector("[data-release-status]");
  const FILE_RE = /^RemBrowse-[A-Za-z0-9_.-]+\.apk$/;
  const SHA_RE = /^[0-9a-f]{64}$/;
  const VERSION_RE = /^[0-9A-Za-z.+-]{1,32}$/;
  const ABI_NAMES = { x86_64: "Intel or AMD", "arm64-v8a": "ARM" };

  // Decimal megabytes with one decimal place, as Android shows file sizes
  const megabytes = (bytes) => (bytes / 1e6).toFixed(1) + " MB";

  // Which file suits this device. Only a guess where the browser doesn't say: Chrome reports the
  // processor through client hints on some devices; otherwise an Android phone is assumed to be
  // ARM (nearly all are), and a tablet or laptop is left for the reader to choose.
  async function guessAbi() {
    const ua = navigator.userAgent || "";
    const uad = navigator.userAgentData;
    const platform = (uad && uad.platform) || "";
    const isAndroidish = /Android|CrOS/i.test(ua) || /Android|Chrome ?OS/i.test(platform);
    if (!isAndroidish) return null;
    try {
      if (uad && uad.getHighEntropyValues) {
        const hints = await uad.getHighEntropyValues(["architecture", "bitness"]);
        if (hints.architecture === "x86") return { abi: "x86_64", sure: true };
        if (hints.architecture === "arm") return { abi: "arm64-v8a", sure: true };
      }
    } catch (_) {
      /* no hints: fall through */
    }
    // Desktop mode sends a fixed "X11; Linux x86_64" string on every device, so the processor in
    // the user-agent string only counts when that string itself says Android or ChromeOS
    if (!/Android|CrOS/i.test(ua)) return null;
    if (/x86_64|x86-64|amd64|i686|x64/i.test(ua)) return { abi: "x86_64", sure: true };
    if (/aarch64|arm64|armv8/i.test(ua)) return { abi: "arm64-v8a", sure: true };
    if (/Android/i.test(ua) && /Mobile/i.test(ua)) return { abi: "arm64-v8a", sure: false };
    return null;
  }

  function recommend(guess, release) {
    if (!guess) return;
    const card = cards.find((c) => c.dataset.abi === guess.abi);
    if (!card) return;
    card.classList.add("is-recommended");
    const badge = card.querySelector("[data-badge]");
    if (badge) badge.textContent = guess.sure ? "For this device" : "Most likely for this phone";
    if (heroLink && guess.sure) {
      const link = card.querySelector("[data-dl]");
      heroLink.href = link.getAttribute("href");
      heroLink.setAttribute("download", link.getAttribute("download") || "");
      if (heroLabel) heroLabel.textContent = "Download for this device";
      if (heroSub) {
        const apk = release && release.apks[guess.abi];
        heroSub.textContent = apk
          ? "Version " + release.versionName + " · " + ABI_NAMES[guess.abi] + " · " + megabytes(apk.size)
          : ABI_NAMES[guess.abi] + " · Android 8.0 or newer";
      }
    }
  }

  function readRelease(data) {
    if (!data || typeof data !== "object") return null;
    if (typeof data.versionName !== "string" || !VERSION_RE.test(data.versionName)) return null;
    if (!Number.isInteger(data.versionCode) || !data.apks || typeof data.apks !== "object") return null;
    const apks = {};
    for (const abi of Object.keys(ABI_NAMES)) {
      const apk = data.apks[abi];
      if (!apk || !FILE_RE.test(apk.file) || !Number.isInteger(apk.size) || apk.size <= 0 || !SHA_RE.test(apk.sha256)) continue;
      apks[abi] = apk;
    }
    return { versionName: data.versionName, versionCode: data.versionCode, apks };
  }

  function fill(release, published) {
    for (const el of document.querySelectorAll("[data-version]")) el.textContent = "Version " + release.versionName;
    const pub = document.querySelector("[data-published]");
    if (pub && published && !Number.isNaN(published.getTime())) {
      pub.textContent = "Published " + published.toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" });
    }
    if (heroSub) heroSub.textContent = "Version " + release.versionName + " · Android 8.0 or newer";
    let missing = 0;
    for (const card of cards) {
      const abi = card.dataset.abi;
      const apk = release.apks[abi];
      if (!apk) {
        missing++;
        continue;
      }
      const link = card.querySelector("[data-dl]");
      // A new address for each version, so no cache anywhere can hand out an older file
      link.href = apk.file + "?v=" + release.versionCode;
      link.setAttribute("download", "RemBrowse-" + release.versionName + "-" + abi + ".apk");
      card.querySelector("[data-dl-label]").textContent = "Download for " + ABI_NAMES[abi] + " · " + megabytes(apk.size);
      const row = card.querySelector("[data-sha-row]");
      row.querySelector("[data-sha]").textContent = apk.sha256;
      row.querySelector("[data-sha]").title = apk.sha256;
      row.hidden = false;
    }
    if (missing && statusLine) {
      statusLine.textContent = "Some download details didn't load. The buttons still download the newest version.";
    }
  }

  const copyStatus = document.querySelector("[data-copy-status]");
  for (const btn of document.querySelectorAll("[data-copy]")) {
    btn.addEventListener("click", async () => {
      const code = btn.parentElement.querySelector("[data-sha]");
      const label = btn.querySelector("span");
      const icon = btn.querySelector("use");
      try {
        await navigator.clipboard.writeText(code.textContent);
        label.textContent = "Copied";
        icon.setAttribute("href", "#i-check");
        if (copyStatus) copyStatus.textContent = "SHA-256 copied.";
      } catch (_) {
        const range = document.createRange();
        range.selectNodeContents(code);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        label.textContent = "Selected";
        if (copyStatus) copyStatus.textContent = "SHA-256 selected. Copy it with Ctrl+C.";
      }
      setTimeout(() => {
        label.textContent = "Copy";
        icon.setAttribute("href", "#i-copy");
      }, 2000);
    });
  }

  async function load() {
    let release = null;
    try {
      const res = await fetch("update.json?t=" + Date.now(), { cache: "no-store" });
      if (res.status === 404) {
        if (statusLine) statusLine.textContent = "No version is published here yet. Check back soon.";
        return;
      }
      if (!res.ok) throw new Error("HTTP " + res.status);
      release = readRelease(await res.json());
      if (!release) throw new Error("unexpected update.json");
      const modified = res.headers.get("last-modified");
      fill(release, modified ? new Date(modified) : null);
    } catch (_) {
      release = null;
      if (statusLine) statusLine.textContent = "The version details didn't load. The buttons still download the newest version.";
    }
    recommend(await guessAbi(), release);
  }

  if (cards.length) load();
})();
