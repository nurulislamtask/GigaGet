// Forced updates.
// When a newer GigaGet release exists, a full-screen popup blocks the app until the user updates.
// The newest version is remembered, so going offline or restarting does not skip the popup.
// If GitHub cannot be reached and no newer version is known, the app keeps working offline.
(function () {
  const REPO = "nurulislamtask/GigaGet";
  const $ = (s) => document.querySelector(s);
  const store = {
    get: (k) => { try { const v = localStorage.getItem("gg." + k); return v == null ? null : JSON.parse(v); } catch { return null; } },
    set: (k, v) => { try { localStorage.setItem("gg." + k, JSON.stringify(v)); } catch {} },
    del: (k) => { try { localStorage.removeItem("gg." + k); } catch {} },
  };
  const parse = (v) => String(v || "").replace(/^v/i, "").split(/[.\-+]/).slice(0, 3).map((n) => parseInt(n, 10) || 0);
  const newer = (a, b) => {
    const x = parse(a), y = parse(b);
    for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
    return false;
  };

  let current = "";
  let target = null;

  function pick(assets, platform) {
    const by = (fn) => assets.find((a) => fn(a.name || ""));
    if (platform === "android") return by((n) => n === "GigaGet.apk") || by((n) => n.endsWith(".apk"));
    if (platform === "windows") return by((n) => n.endsWith("-setup.exe")) || by((n) => n.endsWith(".msi"));
    if (platform === "mac") return by((n) => n.endsWith(".dmg"));
    return by((n) => n.endsWith(".AppImage")) || by((n) => n.endsWith(".deb"));
  }

  function show(info) {
    target = info;
    $("#gateFrom").textContent = "v" + current;
    $("#gateTo").textContent = "v" + String(info.version).replace(/^v/i, "");
    $("#gateHint").textContent = engine.platform === "android"
      ? "Tap Update now, open the downloaded file and choose Install. Your downloads and history are kept."
      : "Download and run the installer. Your downloads and history are kept.";
    $("#gate").hidden = false;
    const app = $(".app");
    if (app) app.inert = true;
    document.body.style.overflow = "hidden";
  }

  async function check() {
    if (!current) return;
    try {
      const r = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
        headers: { Accept: "application/vnd.github+json" },
        cache: "no-store",
      });
      if (!r.ok) return;
      const j = await r.json();
      if (j.tag_name && newer(j.tag_name, current)) {
        const asset = pick(j.assets || [], engine.platform);
        const info = { version: j.tag_name, url: asset ? asset.browser_download_url : j.html_url };
        store.set("latest", info);
        show(info);
      } else {
        store.del("latest");
      }
    } catch { /* offline: keep whatever we already know */ }
  }

  $("#gateBtn").addEventListener("click", () => { if (target) engine.openUrl(target.url); });
  // The popup cannot be dismissed: swallow Escape and Android's back gesture.
  document.addEventListener("keydown", (e) => { if (!$("#gate").hidden && e.key === "Escape") e.preventDefault(); }, true);

  (async function init() {
    current = (await engine.version()) || "";
    const ver = $("#aboutVer");
    if (ver) ver.textContent = current ? "Version " + current : "Preview";
    if (!current) return;
    const saved = store.get("latest");
    if (saved && newer(saved.version, current)) show(saved);
    check();
    setInterval(check, 30 * 60 * 1000);
    window.addEventListener("focus", check);
    document.addEventListener("visibilitychange", () => { if (!document.hidden) check(); });
  })();
})();
