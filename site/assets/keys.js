// RemBrowse key test page: records what the page receives from the keyboard, so input bugs can
// be diagnosed on a real device without developer tools.
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const ce = $("ce");
  const ta = $("ta");
  const logEl = $("log");
  const lines = [];
  const t0 = performance.now();

  const env = [
    "userAgent: " + navigator.userAgent,
    "platform: " + (navigator.userAgentData ? navigator.userAgentData.platform + " (mobile: " + navigator.userAgentData.mobile + ")" : navigator.platform),
    "maxTouchPoints: " + navigator.maxTouchPoints,
    "ontouchstart in window: " + ("ontouchstart" in window),
    "pointer: coarse " + matchMedia("(pointer: coarse)").matches + ", fine " + matchMedia("(pointer: fine)").matches +
      ", any-pointer coarse " + matchMedia("(any-pointer: coarse)").matches + ", any-pointer fine " + matchMedia("(any-pointer: fine)").matches,
    "hover: " + matchMedia("(hover: hover)").matches + ", any-hover " + matchMedia("(any-hover: hover)").matches,
    "screen: " + screen.width + "x" + screen.height + ", window: " + innerWidth + "x" + innerHeight + ", dpr " + devicePixelRatio,
  ];
  $("env").textContent = env.join("\n");

  const where = (el) => (el === ce ? "box1" : el === ta ? "box2" : el && el.tagName ? el.tagName.toLowerCase() : "?");
  const text = (el) => (el === ta ? ta.value : el.textContent);
  const caret = (el) => {
    if (el === ta) return ta.selectionStart + "-" + ta.selectionEnd;
    const s = getSelection();
    if (!s || !s.rangeCount || !el.contains(s.anchorNode)) return "-";
    // Offsets in characters from the start of the box
    const r = document.createRange();
    r.selectNodeContents(el);
    r.setEnd(s.anchorNode, s.anchorOffset);
    const a = r.toString().length;
    r.selectNodeContents(el);
    r.setEnd(s.focusNode, s.focusOffset);
    return a + "-" + r.toString().length;
  };

  function add(line) {
    lines.unshift(((performance.now() - t0) / 1000).toFixed(3) + "  " + line);
    if (lines.length > 400) lines.length = 400;
    logEl.textContent = lines.join("\n");
  }

  for (const el of [ce, ta]) {
    for (const type of ["keydown", "keyup", "keypress"]) {
      el.addEventListener(type, (e) => {
        const mods = (e.ctrlKey ? "Ctrl+" : "") + (e.altKey ? "Alt+" : "") + (e.shiftKey ? "Shift+" : "") + (e.metaKey ? "Meta+" : "");
        add(where(el) + " " + type + " key=" + JSON.stringify(e.key) + " code=" + e.code + " keyCode=" + e.keyCode +
          " " + mods + " composing=" + e.isComposing + " repeat=" + e.repeat + " caret=" + caret(el));
      }, true);
    }
    el.addEventListener("beforeinput", (e) => {
      add(where(el) + " beforeinput type=" + e.inputType + " data=" + JSON.stringify(e.data) + " composing=" + e.isComposing + " caret=" + caret(el));
    }, true);
    el.addEventListener("input", (e) => {
      add(where(el) + " input type=" + e.inputType + " data=" + JSON.stringify(e.data) + " composing=" + e.isComposing +
        " text=" + JSON.stringify(text(el)) + " caret=" + caret(el));
    }, true);
    for (const type of ["compositionstart", "compositionupdate", "compositionend"]) {
      el.addEventListener(type, (e) => add(where(el) + " " + type + " data=" + JSON.stringify(e.data) + " caret=" + caret(el)), true);
    }
    el.addEventListener("focus", () => add(where(el) + " focus"));
    el.addEventListener("blur", () => add(where(el) + " blur"));
  }
  document.addEventListener("selectionchange", () => {
    const active = document.activeElement;
    if (active === ce || active === ta) add(where(active) + " selectionchange caret=" + caret(active));
  });

  // Box 1 behaves like a chat box: Enter without Shift sends (and is cancelled), as claude.ai does on desktop
  ce.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      $("sent").textContent = "Sent on Enter: " + JSON.stringify(ce.textContent);
      add("box1 >>> SENT (the page got a real Enter keydown and cancelled it)");
      ce.textContent = "";
    }
  });

  $("clear").addEventListener("click", () => {
    lines.length = 0;
    logEl.textContent = "";
    $("sent").textContent = "";
  });
  $("copy").addEventListener("click", async () => {
    const all = "RemBrowse key test\n" + env.join("\n") + "\n\nEvents, oldest first:\n" + lines.slice().reverse().join("\n");
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
