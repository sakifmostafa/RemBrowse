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

    // Which panes and dividers each layout shows (the CSS hides the rest; keep the two in step)
    const SHOWN = {
      side: [".p1", ".p2", ".gv1"],
      stacked: [".p1", ".p2", ".gh"],
      grid: [".p1", ".p2", ".p3", ".p4", ".gv1", ".gv2", ".gh"],
      onetwo: [".p1", ".p2", ".p3", ".gv1", ".gh"],
    };
    const parts = Array.from(win.querySelectorAll(".pane, .grip"));
    const shownIn = (name) => new Set(SHOWN[name].map((sel) => win.querySelector(sel)));
    const MOVE = { duration: 420, easing: "cubic-bezier(0.2, 0.7, 0.2, 1)" };
    const EXIT = { duration: 160, easing: "cubic-bezier(0.4, 0, 1, 1)" };
    const ENTER_EASE = "cubic-bezier(0.2, 0.7, 0.2, 1)";
    const near = (a, b, by) => Math.abs(a - b) <= by;

    // Switches layout. The new geometry is set at once by the CSS; the change is then played with
    // transforms and opacity only (FLIP: from where each part is on screen to where it now
    // belongs), so nothing is laid out again while it moves. A pane whose size changes fades its
    // contents out and back in rather than showing them stretched. Every animation starts from
    // what is on screen at that moment, so a switch made mid-move carries on smoothly, and none
    // of them is needed for the final state, which the CSS already holds.
    const morph = (name) => {
      if (reduceMotion.matches || typeof win.animate !== "function") {
        setLayout(name);
        return;
      }
      const now = new Map(
        parts.map((el) => {
          const page = el.querySelector(".pg");
          return [el, {
            rect: el.getBoundingClientRect(),
            opacity: parseFloat(getComputedStyle(el).opacity),
            content: page ? parseFloat(getComputedStyle(page).opacity) : 1,
          }];
        })
      );
      for (const el of win.querySelectorAll(".pane, .grip, .pg")) el.getAnimations().forEach((a) => a.cancel());
      setLayout(name);
      const after = shownIn(name);
      let entering = 0;
      for (const el of parts) {
        const was = now.get(el);
        const isGrip = el.classList.contains("grip");
        const shown = after.has(el);
        const onScreen = was.opacity > 0.02;
        if (!onScreen && !shown) continue;
        if (!onScreen) {
          // Appears: grows into place from its own centre
          el.animate(
            [
              { opacity: 0, transformOrigin: "50% 50%", transform: isGrip ? "translate(-50%, -50%) scale(0.8)" : "scale(0.96)" },
              { opacity: 1, transformOrigin: "50% 50%", transform: isGrip ? "translate(-50%, -50%)" : "none" },
            ],
            { duration: 260, delay: 140 + entering++ * 40, easing: ENTER_EASE, fill: "backwards" }
          );
          continue;
        }
        const to = el.getBoundingClientRect();
        if (to.width && to.height) {
          const dx = was.rect.left - to.left;
          const dy = was.rect.top - to.top;
          if (isGrip) {
            if (!near(dx, 0, 0.5) || !near(dy, 0, 0.5)) {
              el.animate([{ transform: "translate(calc(-50% + " + dx + "px), calc(-50% + " + dy + "px))" }, { transform: "translate(-50%, -50%)" }], MOVE);
            }
          } else {
            const sx = was.rect.width / to.width;
            const sy = was.rect.height / to.height;
            if (!near(dx, 0, 0.5) || !near(dy, 0, 0.5) || !near(sx, 1, 0.01) || !near(sy, 1, 0.01)) {
              el.animate([{ transform: "translate(" + dx + "px, " + dy + "px) scale(" + sx + ", " + sy + ")" }, { transform: "none" }], MOVE);
              const page = el.querySelector(".pg");
              if (page && (!near(sx, 1, 0.02) || !near(sy, 1, 0.02))) {
                page.animate([{ opacity: was.content }, { opacity: 0, offset: 0.15 }, { opacity: 0, offset: 0.55 }, { opacity: 1 }], MOVE);
              }
            }
          }
        }
        if (!shown) {
          // Leaves: fades out from wherever its opacity is, kept visible until the fade ends
          el.animate([{ opacity: was.opacity, visibility: "visible" }, { opacity: 0, visibility: "visible" }], EXIT);
        } else if (was.opacity < 0.99) {
          el.animate([{ opacity: was.opacity }, { opacity: 1 }], { duration: 200, easing: ENTER_EASE });
        }
      }
    };

    const setLayout = (name) => {
      win.dataset.layout = name;
      index = order.indexOf(name);
      for (const b of buttons) b.setAttribute("aria-pressed", String(b.dataset.layoutBtn === name));
      win.setAttribute("aria-label", "A RemBrowse window with " + descriptions[name] + ".");
    };

    const sync = () => {
      clearInterval(timer);
      timer = null;
      if (playing && onScreen && !held && !document.hidden) {
        timer = setInterval(() => morph(order[(index + 1) % order.length]), 3400);
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
        morph(b.dataset.layoutBtn);
        if (status) status.textContent = names[b.dataset.layoutBtn] + ": " + descriptions[b.dataset.layoutBtn] + ".";
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

  // ---------- Scroll reveal ----------
  // Content below the first screen rises into place as it comes into view, items in the same row
  // one after another. Nothing is hidden without JavaScript, with reduced motion, or if it is
  // already on screen when the page loads.
  const reveals = Array.from(document.querySelectorAll(".reveal"));
  if (reveals.length && "IntersectionObserver" in window && !reduceMotion.matches) {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          entry.target.classList.remove("is-pending");
          observer.unobserve(entry.target);
        }
      },
      { rootMargin: "0px 0px -8% 0px" }
    );
    const fold = window.innerHeight;
    let order = 0;
    let row = null;
    for (const el of reveals) {
      if (el.getBoundingClientRect().top < fold) continue;
      order = el.parentElement === row ? order + 1 : 0;
      row = el.parentElement;
      el.style.transitionDelay = Math.min(order, 4) * 80 + "ms";
      el.classList.add("is-pending");
      observer.observe(el);
    }
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
    if (card.parentElement) card.parentElement.classList.add("has-pick");
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
          : ABI_NAMES[guess.abi] + " · Android 13 or later";
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
    for (const el of document.querySelectorAll("[data-version-short]")) el.textContent = release.versionName;
    const pub = document.querySelector("[data-published]");
    if (pub && published && !Number.isNaN(published.getTime())) {
      pub.textContent = "Published " + published.toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" });
    }
    if (heroSub) heroSub.textContent = "Version " + release.versionName + " · Free · Android 13 or later";
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
      // Seen: "Download · 89.6 MB" (the card's heading names the processor). Heard: "Download for
      // Intel or AMD · 89.6 MB", which still begins with the visible words.
      const label = card.querySelector("[data-dl-label]");
      const hidden = document.createElement("span");
      hidden.className = "visually-hidden";
      hidden.textContent = " for " + ABI_NAMES[abi];
      label.replaceChildren("Download", hidden, " · " + megabytes(apk.size));
      const row = card.querySelector("[data-sha-row]");
      row.querySelector("[data-sha]").textContent = apk.sha256;
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
        return null;
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
    return release;
  }

  // The VirusTotal result for this release (virustotal.json, written by scripts/virustotal-scan.sh).
  // Shown only if it is for the same version and the same SHA-256s as update.json; otherwise
  // nothing is shown. The report address is built here from the hash, not taken from the file.
  async function showScan(release) {
    if (!release) return;
    try {
      const res = await fetch("virustotal.json?t=" + Date.now(), { cache: "no-store" });
      if (!res.ok) return;
      const scan = await res.json();
      if (!scan || scan.dryRun || scan.versionName !== release.versionName || !scan.files || typeof scan.files !== "object") return;
      const lines = [];
      for (const card of cards) {
        const apk = release.apks[card.dataset.abi];
        if (!apk) continue;
        const f = scan.files[apk.file];
        const counts = f && [f.malicious, f.suspicious, f.engines];
        if (!f || f.sha256 !== apk.sha256 || !counts.every((n) => Number.isInteger(n) && n >= 0) || f.engines < 1) return;
        lines.push([card, apk, f]);
      }
      for (const [card, apk, f] of lines) {
        const p = document.createElement("p");
        p.className = "vt";
        const a = document.createElement("a");
        a.href = "https://www.virustotal.com/gui/file/" + apk.sha256;
        a.textContent = "View report";
        a.setAttribute("aria-label", "View the VirusTotal report for this download");
        p.append("Scanned by VirusTotal: " + (f.malicious + f.suspicious) + " of " + f.engines + " engines flagged it \u00b7 ", a);
        card.append(p);
      }
    } catch (_) {
      /* no scan result: show nothing */
    }
  }

  if (cards.length) load().then(showScan);
})();
