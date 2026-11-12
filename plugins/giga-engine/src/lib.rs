use tauri::{
    plugin::{Builder, TauriPlugin},
    Runtime,
};

/// Registers the Kotlin `GigaEnginePlugin` on Android. On other platforms this is a no-op,
/// because desktop uses the Rust engine in `src-tauri/src/engine.rs`.
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("giga-engine")
        .setup(|_app, _api| {
            #[cfg(target_os = "android")]
            _api.register_android_plugin("com.gigaget.engine", "GigaEnginePlugin")?;
            Ok(())
        })
        .build()
}
