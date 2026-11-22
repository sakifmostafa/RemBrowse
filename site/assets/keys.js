// RemBrowse key test page: records what the page receives from the keyboard, so input bugs can
// be diagnosed on a real device without developer tools.
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const ce = $("ce");
  const ta = $("ta");
  const pm = $("pm");
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

  const where = (el) => (el === ce ? "box1" : el === ta ? "box2" : el === pm ? "box3" : el && el.tagName ? el.tagName.toLowerCase() : "?");
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

  for (const el of [ce, ta, pm]) {
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
    if (active === ce || active === ta || active === pm) add(where(active) + " selectionchange caret=" + caret(active));
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

  // Box 3 keeps its text in one <p>, as ProseMirror editors do (claude.ai, Telegram). Ctrl+A selects
  // the box's children, so the selection starts before the <p>; every edit is cancelled and the
  // paragraph is drawn again from the new text, with the caret put after what was typed.
  function render(value, at) {
    const p = document.createElement("p");
    if (value) p.textContent = value;
    else p.appendChild(document.createElement("br"));
    pm.replaceChildren(p);
    if (value) getSelection().collapse(p.firstChild, at);
    else getSelection().collapse(p, 0);
  }
  pm.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "a") {
      e.preventDefault();
      getSelection().setBaseAndExtent(pm, 0, pm, pm.childNodes.length);
      add("box3 >>> SELECTED ALL caret=" + caret(pm));
    }
  });
  pm.addEventListener("beforeinput", (e) => {
    // A word being composed is left to the browser
    if (e.isComposing || e.inputType.startsWith("insertComposition")) return;
    const sel = caret(pm).split("-").map(Number);
    if (sel.length !== 2 || sel.some(Number.isNaN)) return;
    const value = pm.textContent;
    let from = Math.min(sel[0], sel[1]);
    let to = Math.max(sel[0], sel[1]);
    let insert = "";
    if (e.inputType === "insertText") insert = e.data || "";
    else if (e.inputType === "deleteContentBackward") from = from === to ? Math.max(0, from - 1) : from;
    else if (e.inputType === "deleteContentForward") to = from === to ? Math.min(value.length, to + 1) : to;
    else if (e.inputType !== "insertParagraph" && e.inputType !== "insertLineBreak") return;
    else to = from; // one paragraph only: Enter does nothing
    e.preventDefault();
    render(value.slice(0, from) + insert + value.slice(to), from + insert.length);
    add("box3 >>> REDRAWN text=" + JSON.stringify(pm.textContent) + " caret=" + caret(pm));
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
