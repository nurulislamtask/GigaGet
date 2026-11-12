// Bridge between the UI and the native engine.
//  - Desktop (Windows/macOS/Linux): Rust commands in src-tauri
//  - Android: the giga-engine Kotlin plugin (youtubedl-android)
//  - Plain browser (preview only): a small fake engine so the UI can be tested
(function () {
  const T = window.__TAURI__;
  const isAndroid = /Android/i.test(navigator.userAgent);
  const PLUGIN = "giga-engine";
  let engine;

  if (T && T.core) {
    const call = (cmd, args) =>
      T.core.invoke(isAndroid ? `plugin:${PLUGIN}|${cmd}` : cmd, args || {});
    engine = {
      native: true,
      probe: (url) => call("probe", { url }),
      start: (req) => call("start", req),
      cancel: (id) => call("cancel", { id }),
      info: () => call("info"),
      async onProgress(cb) {
        if (isAndroid) await T.core.addPluginListener(PLUGIN, "progress", cb);
        else await T.event.listen("giga://progress", (e) => cb(e.payload));
      },
      async clipboard() {
        try { return await navigator.clipboard.readText(); } catch { return ""; }
      },
      async open(path) {
        try { await call("open_file", { path }); } catch {}
      },
    };
  } else {
    const timers = {};
    let listener = () => {};
    engine = {
      native: false,
      probe: async (url) => {
        await new Promise((r) => setTimeout(r, 500));
        return {
          title: "Sample video from " + new URL(url).hostname,
          thumbnail: "",
          duration: 214,
          uploader: "Preview mode",
          qualities: ["best", "1080", "720", "480", "audio"],
        };
      },
      start: async ({ id }) => {
        let p = 0;
        timers[id] = setInterval(() => {
          p += 4 + Math.random() * 8;
          if (p >= 100) {
            clearInterval(timers[id]);
            listener({ id, status: "done", percent: 100, file: "Downloads/GigaGet/sample.mp4" });
          } else {
            listener({ id, status: "downloading", percent: p, speed: "3.2MiB/s", eta: "0:0" + Math.ceil((100 - p) / 12) });
          }
        }, 400);
        return id;
      },
      cancel: async (id) => clearInterval(timers[id]),
      info: async () => ({ dir: "Downloads/GigaGet", engine: "preview" }),
      onProgress: async (cb) => { listener = cb; },
      clipboard: async () => { try { return await navigator.clipboard.readText(); } catch { return ""; } },
      open: async () => {},
    };
  }
  window.engine = engine;
})();
