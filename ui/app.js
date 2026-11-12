(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const store = {
    get: (k, d) => { try { const v = localStorage.getItem("gg." + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set: (k, v) => { try { localStorage.setItem("gg." + k, JSON.stringify(v)); } catch {} },
  };

  // Everything the user chose last time is remembered, so nothing needs configuring twice.
  let items = store.get("items", []);
  let prefs = Object.assign({ quality: "best", clip: true, theme: "auto", saveDir: "", saveLoc: "downloads" }, store.get("prefs", {}));
  let filter = "all";
  let probed = null;
  let lastClip = store.get("lastClip", "");

  const QLABEL = { best: "Best", "2160": "4K", "1440": "1440p", "1080": "1080p", "720": "720p", "480": "480p", "360": "360p", audio: "Audio MP3" };
  const URL_RE = /^https?:\/\/\S+$/i;
  const uid = () => Math.random().toString(36).slice(2, 10);
  const savePrefs = () => store.set("prefs", prefs);
  const saveItems = () => store.set("items", items.slice(0, 200));

  function applyTheme() { document.documentElement.dataset.theme = prefs.theme; }
  function fmtDur(s) {
    if (!s) return "";
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = Math.floor(s % 60);
    return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(x).padStart(2, "0");
  }
  const esc = (t) => String(t ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  /* ---------- navigation ---------- */
  $$(".nav").forEach((b) => b.addEventListener("click", () => {
    $$(".nav").forEach((n) => n.classList.toggle("on", n.dataset.view === b.dataset.view));
    $$(".view").forEach((v) => (v.hidden = v.id !== "view-" + b.dataset.view));
  }));

  /* ---------- grabbing a link ---------- */
  function showErr(msg) { const e = $("#err"); e.textContent = msg || ""; e.hidden = !msg; }

  async function probe(url) {
    showErr("");
    const btn = $("#grabBtn");
    btn.disabled = true; btn.textContent = "…";
    try {
      const info = await engine.probe(url);
      probed = { url, info };
      renderPreview();
    } catch (e) {
      probed = null; $("#preview").hidden = true;
      showErr("Could not read that link. " + (e && e.message ? e.message : e || ""));
    } finally { btn.disabled = false; btn.textContent = "Get"; }
  }

  function renderPreview() {
    const { info } = probed;
    const quals = info.qualities && info.qualities.length ? info.qualities : ["best", "1080", "720", "480", "audio"];
    if (!quals.includes(prefs.quality)) prefs.quality = quals.includes("best") ? "best" : quals[0];
    $("#pThumb").src = info.thumbnail || "";
    $("#pThumb").style.display = info.thumbnail ? "" : "none";
    $("#pTitle").textContent = info.title || probed.url;
    $("#pMeta").textContent = [info.uploader, fmtDur(info.duration)].filter(Boolean).join(" · ");
    const box = $("#chips");
    box.innerHTML = "";
    quals.forEach((q) => {
      const b = document.createElement("button");
      b.type = "button"; b.className = "chip"; b.setAttribute("role", "radio");
      b.setAttribute("aria-checked", String(q === prefs.quality));
      b.textContent = QLABEL[q] || q + "p";
      b.onclick = () => { prefs.quality = q; savePrefs(); renderPreview(); };
      box.appendChild(b);
    });
    $("#preview").hidden = false;
  }

  async function startDownload(url, info, quality) {
    const id = uid();
    const item = { id, url, title: (info && info.title) || url, thumb: (info && info.thumbnail) || "", quality,
      status: "downloading", percent: 0, speed: "", eta: "", file: "", error: "", at: Date.now() };
    items.unshift(item); saveItems(); render();
    try { await engine.start({ id, url, quality, dir: prefs.saveDir || "", location: prefs.saveLoc || "downloads" }); }
    catch (e) { patch(id, { status: "error", error: String((e && e.message) || e) }); }
  }

  $("#grab").addEventListener("submit", (e) => { e.preventDefault(); const u = $("#url").value.trim(); if (u) probe(u); });
  $("#url").addEventListener("paste", () => setTimeout(() => { const u = $("#url").value.trim(); if (URL_RE.test(u)) probe(u); }, 0));
  $("#pGo").addEventListener("click", () => {
    if (!probed) return;
    startDownload(probed.url, probed.info, prefs.quality);
    probed = null; $("#preview").hidden = true; $("#url").value = "";
  });
  $("#pX").addEventListener("click", () => { probed = null; $("#preview").hidden = true; });

  /* ---------- clipboard: copy a link, open the app, one tap ---------- */
  async function checkClipboard() {
    if (!prefs.clip) return;
    const t = ((await engine.clipboard()) || "").trim();
    if (!URL_RE.test(t) || t === lastClip) return;
    $("#clipUrl").textContent = t; $("#clip").hidden = false; $("#clip").dataset.url = t;
  }
  function dismissClip() { const u = $("#clip").dataset.url; if (u) { lastClip = u; store.set("lastClip", u); } $("#clip").hidden = true; }
  $("#clipX").addEventListener("click", dismissClip);
  $("#clipGo").addEventListener("click", async () => {
    const u = $("#clip").dataset.url; dismissClip();
    try { const info = await engine.probe(u); startDownload(u, info, prefs.quality); }
    catch (e) { showErr("Could not read that link. " + ((e && e.message) || "")); }
  });
  window.addEventListener("focus", checkClipboard);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) checkClipboard(); });

  /* ---------- list ---------- */
  function patch(id, p) { const it = items.find((x) => x.id === id); if (!it) return; Object.assign(it, p); saveItems(); render(); }

  function render() {
    const list = $("#list");
    const shown = items.filter((i) => filter === "all" || (filter === "active" ? i.status === "downloading" : i.status === "done"));
    list.innerHTML = "";
    shown.forEach((it) => {
      const li = document.createElement("li");
      li.className = "item " + it.status;
      const sub = it.status === "downloading" ? [Math.floor(it.percent) + "%", it.speed, it.eta && "ETA " + it.eta].filter(Boolean).join(" · ")
        : it.status === "done" ? "Saved · " + (QLABEL[it.quality] || it.quality)
        : it.status === "error" ? "Failed: " + (it.error || "unknown error") : "";
      li.innerHTML = `
        ${it.thumb ? `<img alt="" src="${esc(it.thumb)}">` : `<div class="ph" aria-hidden="true">↓</div>`}
        <div><div class="i-title">${esc(it.title)}</div><div class="i-sub">${esc(sub)}</div>
          <div class="bar"><i style="width:${it.percent || 0}%"></i></div></div>
        <div class="acts"></div>`;
      const acts = $(".acts", li);
      const mk = (label, txt, fn) => { const b = document.createElement("button"); b.title = label; b.setAttribute("aria-label", label); b.textContent = txt; b.onclick = fn; acts.appendChild(b); };
      if (it.status === "downloading") mk("Cancel", "✕", async () => { await engine.cancel(it.id); patch(it.id, { status: "error", error: "cancelled" }); });
      if (it.status === "error") mk("Retry", "↻", () => { items = items.filter((x) => x.id !== it.id); saveItems(); startDownload(it.url, { title: it.title, thumbnail: it.thumb }, it.quality); });
      if (it.status === "done" && it.file) mk("Open", "▶", () => engine.open(it.file));
      if (it.status !== "downloading") mk("Remove", "🗑", () => { items = items.filter((x) => x.id !== it.id); saveItems(); render(); });
      list.appendChild(li);
    });
    $("#empty").hidden = shown.length > 0;
  }

  $$(".tab").forEach((t) => t.addEventListener("click", () => {
    filter = t.dataset.f; $$(".tab").forEach((x) => x.classList.toggle("on", x === t)); render();
  }));
  $("#clear").addEventListener("click", () => { items = items.filter((i) => i.status === "downloading"); saveItems(); render(); });

  /* ---------- progress from the engine ---------- */
  engine.onProgress((p) => {
    if (!p || !p.id) return;
    const upd = { percent: p.percent ?? 0, speed: p.speed || "", eta: p.eta || "" };
    if (p.status === "done") Object.assign(upd, { status: "done", percent: 100, file: p.file || "" });
    else if (p.status === "error") Object.assign(upd, { status: "error", error: p.error || "" });
    else if (p.status === "processing") upd.speed = "processing…";
    patch(p.id, upd);
  });

  /* ---------- settings (all optional) ---------- */
  $("#optClip").checked = prefs.clip;
  $("#optClip").addEventListener("change", (e) => { prefs.clip = e.target.checked; savePrefs(); });
  $("#optTheme").value = prefs.theme;
  $("#optTheme").addEventListener("change", (e) => { prefs.theme = e.target.value; savePrefs(); applyTheme(); });
  // Save location: a folder picker on computers, two simple choices on Android (its storage rules).
  const isAndroid = engine.platform === "android";
  let defaultDir = "Downloads/GigaGet";
  function showDir() {
    $("#dir").textContent = prefs.saveDir || defaultDir;
    $("#dir").title = prefs.saveDir || defaultDir;
    $("#resetDir").hidden = !prefs.saveDir;
  }
  $("#rowDesk").hidden = isAndroid;
  $("#rowAnd").hidden = !isAndroid;
  $("#optLoc").value = prefs.saveLoc;
  $("#optLoc").addEventListener("change", (e) => { prefs.saveLoc = e.target.value; savePrefs(); });
  $("#pickDir").addEventListener("click", async () => {
    const d = await engine.pickFolder();
    if (d) { prefs.saveDir = d; savePrefs(); showDir(); }
  });
  $("#resetDir").addEventListener("click", () => { prefs.saveDir = ""; savePrefs(); showDir(); });
  engine.info().then((i) => { if (i.dir) defaultDir = i.dir; if (i.engine) $("#engineVer").textContent = i.engine; showDir(); }).catch(showDir);

  // Paste button: fetch the clipboard on tap (works on phones where silent clipboard reads are blocked).
  $("#pasteBtn").addEventListener("click", async () => {
    const t = ((await engine.clipboard()) || "").trim();
    if (!t) { showErr("Nothing to paste. Copy a link first."); return; }
    $("#url").value = t;
    if (URL_RE.test(t)) probe(t); else showErr("That does not look like a link.");
  });

  // Links in the About card open in the system browser.
  $$("[data-href]").forEach((b) => b.addEventListener("click", () => engine.openUrl(b.dataset.href)));

  // After an app restart, anything that was mid-download is shown as interrupted so it can be retried.
  items.forEach((i) => { if (i.status === "downloading") { i.status = "error"; i.error = "interrupted"; } });
  applyTheme(); render(); checkClipboard();
})();
