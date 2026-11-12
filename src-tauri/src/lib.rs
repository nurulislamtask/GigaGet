#[cfg(desktop)]
mod engine;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let builder = tauri::Builder::default().plugin(tauri_plugin_opener::init());

    // Desktop: yt-dlp + FFmpeg are shipped inside the app and driven from Rust.
    #[cfg(desktop)]
    let builder = builder
        .manage(engine::Engine::default())
        .setup(|app| {
            engine::spawn_background_update(app.handle().clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            engine::probe,
            engine::start,
            engine::cancel,
            engine::info,
            engine::open_file
        ]);

    // Android: the same UI talks to the Kotlin plugin (youtubedl-android).
    #[cfg(mobile)]
    let builder = builder.plugin(giga_engine::init());

    builder
        .run(tauri::generate_context!())
        .expect("error while running GigaGet");
}
