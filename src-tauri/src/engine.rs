//! Desktop download engine.
//!
//! Zero-setup design: the installer ships yt-dlp and FFmpeg next to the app
//! (`gigaget-ytdlp`, `gigaget-ffmpeg`). On first launch yt-dlp is copied into the
//! app's own data folder, which is writable, so it can update itself in the
//! background. Nothing has to be installed or configured by the user.

use serde::Serialize;
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::Command,
    sync::{oneshot, Mutex},
};

#[derive(Default)]
pub struct Engine {
    jobs: Mutex<HashMap<String, oneshot::Sender<()>>>,
}

#[derive(Serialize)]
pub struct Probe {
    title: String,
    thumbnail: String,
    duration: f64,
    uploader: String,
    qualities: Vec<String>,
}

#[derive(Serialize, Clone)]
struct Progress {
    id: String,
    status: String,
    percent: f64,
    speed: String,
    eta: String,
    file: Option<String>,
    error: Option<String>,
}

fn bin(name: &str) -> String {
    if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.to_string()
    }
}

fn exe_dir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(Path::to_path_buf))
        .unwrap_or_default()
}

fn new_cmd(path: &Path) -> Command {
    let mut c = Command::new(path);
    c.kill_on_drop(true);
    #[cfg(windows)]
    c.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    c
}

/// Path to the writable copy of yt-dlp, seeding it from the bundled one on first run.
fn ytdlp_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("bin");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let target = dir.join(bin("yt-dlp"));
    if !target.exists() {
        let seed = exe_dir().join(bin("gigaget-ytdlp"));
        std::fs::copy(&seed, &target)
            .map_err(|e| format!("bundled yt-dlp not found ({}): {e}", seed.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&target, std::fs::Permissions::from_mode(0o755));
        }
    }
    Ok(target)
}

fn ffmpeg_path() -> Option<PathBuf> {
    let p = exe_dir().join(bin("gigaget-ffmpeg"));
    p.exists().then_some(p)
}

fn out_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let base = app
        .path()
        .download_dir()
        .or_else(|_| app.path().home_dir())
        .map_err(|e| e.to_string())?;
    let dir = base.join("GigaGet");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

/// Keep yt-dlp fresh: sites change constantly, so update quietly on every launch.
pub fn spawn_background_update(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        if let Ok(path) = ytdlp_path(&app) {
            let _ = tokio::time::timeout(
                Duration::from_secs(120),
                new_cmd(&path).arg("-U").stdout(Stdio::null()).stderr(Stdio::null()).status(),
            )
            .await;
        }
    });
}

#[tauri::command]
pub async fn probe(app: AppHandle, url: String) -> Result<Probe, String> {
    let yt = ytdlp_path(&app)?;
    let out = tokio::time::timeout(
        Duration::from_secs(60),
        new_cmd(&yt)
            .args(["-J", "--no-playlist", "--no-warnings", "--ignore-config", &url])
            .stdin(Stdio::null())
            .output(),
    )
    .await
    .map_err(|_| "timed out reading the link".to_string())?
    .map_err(|e| e.to_string())?;

    if !out.status.success() {
        return Err(last_error(&String::from_utf8_lossy(&out.stderr)));
    }
    let v: serde_json::Value =
        serde_json::from_slice(&out.stdout).map_err(|e| format!("unexpected reply: {e}"))?;

    let max_h = v["formats"]
        .as_array()
        .map(|fs| fs.iter().filter_map(|f| f["height"].as_u64()).max().unwrap_or(0))
        .unwrap_or(0);
    let has_audio = v["formats"]
        .as_array()
        .map(|fs| fs.iter().any(|f| f["acodec"].as_str().map_or(false, |a| a != "none")))
        .unwrap_or(false);

    let mut qualities = vec!["best".to_string()];
    for h in [2160u64, 1440, 1080, 720, 480, 360] {
        if max_h >= h {
            qualities.push(h.to_string());
        }
    }
    if has_audio && ffmpeg_path().is_some() {
        qualities.push("audio".into());
    }

    let s = |k: &str| v[k].as_str().unwrap_or("").to_string();
    Ok(Probe {
        title: s("title"),
        thumbnail: s("thumbnail"),
        duration: v["duration"].as_f64().unwrap_or(0.0),
        uploader: v["uploader"].as_str().or(v["channel"].as_str()).unwrap_or("").to_string(),
        qualities,
    })
}

fn last_error(stderr: &str) -> String {
    stderr
        .lines()
        .rev()
        .find(|l| !l.trim().is_empty())
        .unwrap_or("download failed")
        .trim()
        .trim_start_matches("ERROR:")
        .trim()
        .to_string()
}

fn emit(app: &AppHandle, p: Progress) {
    let _ = app.emit("giga://progress", p);
}

fn ev(id: &str, status: &str) -> Progress {
    Progress { id: id.into(), status: status.into(), percent: 0.0, speed: String::new(), eta: String::new(), file: None, error: None }
}

#[tauri::command]
pub async fn start(
    app: AppHandle,
    state: State<'_, Engine>,
    id: String,
    url: String,
    quality: String,
) -> Result<String, String> {
    let yt = ytdlp_path(&app)?;
    let dir = out_dir(&app)?;
    let ffmpeg = ffmpeg_path();

    let mut args: Vec<String> = [
        "--newline", "--progress", "--quiet", "--no-simulate", "--no-warnings", "--ignore-config",
        "--no-playlist", "--continue", "--no-mtime", "--windows-filenames",
        "--retries", "10", "--fragment-retries", "10", "--concurrent-fragments", "8",
        "--progress-template",
        "download:GIGA|%(progress.status)s|%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s",
        "--print", "after_move:GIGAFILE|%(filepath)s",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();

    args.push("-o".into());
    args.push(dir.join("%(title).120B [%(id)s].%(ext)s").to_string_lossy().into_owned());

    if let Some(ff) = &ffmpeg {
        args.push("--ffmpeg-location".into());
        args.push(ff.to_string_lossy().into_owned());
    }

    match (quality.as_str(), ffmpeg.is_some()) {
        ("audio", true) => args.extend(["-f", "bestaudio/best", "-x", "--audio-format", "mp3", "--audio-quality", "0"].map(String::from)),
        ("best", true) => args.extend(["-f", "bv*+ba/b", "-S", "res,vcodec:h264,acodec:aac", "--merge-output-format", "mp4"].map(String::from)),
        ("best", false) | ("audio", false) => args.extend(["-f", "b"].map(String::from)),
        (h, true) => {
            args.push("-f".into());
            args.push(format!("bv*[height<={h}]+ba/b[height<={h}]/b"));
            args.extend(["-S", "res,vcodec:h264,acodec:aac", "--merge-output-format", "mp4"].map(String::from));
        }
        (h, false) => {
            args.push("-f".into());
            args.push(format!("b[height<={h}]/b"));
        }
    }
    args.push(url);

    let (cancel_tx, mut cancel_rx) = oneshot::channel::<()>();
    state.jobs.lock().await.insert(id.clone(), cancel_tx);

    let app2 = app.clone();
    let id2 = id.clone();
    tauri::async_runtime::spawn(async move {
        let mut child = match new_cmd(&yt)
            .args(&args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
        {
            Ok(c) => c,
            Err(e) => {
                let mut p = ev(&id2, "error");
                p.error = Some(e.to_string());
                emit(&app2, p);
                return;
            }
        };

        let stdout = child.stdout.take().unwrap();
        let stderr = child.stderr.take().unwrap();
        let err_task = tauri::async_runtime::spawn(async move {
            let mut all = String::new();
            let mut lines = BufReader::new(stderr).lines();
            while let Ok(Some(l)) = lines.next_line().await {
                all.push_str(&l);
                all.push('\n');
            }
            all
        });

        let mut file: Option<String> = None;
        let mut lines = BufReader::new(stdout).lines();
        let mut cancelled = false;

        loop {
            tokio::select! {
                _ = &mut cancel_rx => {
                    cancelled = true;
                    let _ = child.kill().await;
                    break;
                }
                line = lines.next_line() => {
                    match line {
                        Ok(Some(l)) => {
                            if let Some(rest) = l.strip_prefix("GIGAFILE|") {
                                file = Some(rest.trim().to_string());
                            } else if let Some(rest) = l.strip_prefix("GIGA|") {
                                let f: Vec<&str> = rest.split('|').collect();
                                if f.len() >= 4 {
                                    let status = if f[0] == "finished" { "processing" } else { "downloading" };
                                    let pct: f64 = f[1].chars().filter(|c| c.is_ascii_digit() || *c == '.').collect::<String>().parse().unwrap_or(0.0);
                                    let mut p = ev(&id2, status);
                                    p.percent = pct;
                                    p.speed = f[2].trim().to_string();
                                    p.eta = f[3].trim().to_string();
                                    emit(&app2, p);
                                }
                            }
                        }
                        _ => break,
                    }
                }
            }
        }

        let status = child.wait().await;
        let stderr_text = err_task.await.unwrap_or_default();

        if !cancelled {
            match status {
                Ok(s) if s.success() => {
                    let mut p = ev(&id2, "done");
                    p.percent = 100.0;
                    p.file = file;
                    emit(&app2, p);
                }
                _ => {
                    let mut p = ev(&id2, "error");
                    p.error = Some(last_error(&stderr_text));
                    emit(&app2, p);
                }
            }
        }
        if let Some(st) = app2.try_state::<Engine>() {
            st.jobs.lock().await.remove(&id2);
        }
    });

    Ok(id)
}

#[tauri::command]
pub async fn cancel(state: State<'_, Engine>, id: String) -> Result<(), String> {
    if let Some(tx) = state.jobs.lock().await.remove(&id) {
        let _ = tx.send(());
    }
    Ok(())
}

#[tauri::command]
pub async fn info(app: AppHandle) -> Result<serde_json::Value, String> {
    let dir = out_dir(&app)?;
    let ver = match ytdlp_path(&app) {
        Ok(p) => new_cmd(&p)
            .arg("--version")
            .output()
            .await
            .map(|o| format!("yt-dlp {}", String::from_utf8_lossy(&o.stdout).trim()))
            .unwrap_or_else(|_| "built in".into()),
        Err(_) => "built in".into(),
    };
    Ok(serde_json::json!({ "dir": dir.to_string_lossy(), "engine": ver }))
}

#[tauri::command]
pub fn open_file(app: AppHandle, path: String) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    app.opener()
        .open_path(path, None::<&str>)
        .map_err(|e| e.to_string())
}
