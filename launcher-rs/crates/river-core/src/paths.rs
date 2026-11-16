//! Where the launcher keeps its files.
//!
//! The Rust launcher has to read and write exactly the folders the Electron launcher
//! uses, or everyone's accounts, settings and instances would look empty after updating.
//! Electron derives these from `app.getPath("appData")` and the package name, so the
//! same rules are reproduced here rather than invented.

use std::path::{Path, PathBuf};

/// Electron's package name, which is also its `userData` folder name.
pub const APP_DIR: &str = "river-client-launcher";
/// `productName`, the folder the game data lives in (instances, bridge).
pub const PRODUCT_DIR: &str = "River Client";

/// The roaming application-data folder, matching Electron's `appData`.
///
/// Windows uses `%APPDATA%`, macOS `~/Library/Application Support`, and everything else
/// `$XDG_CONFIG_HOME` or `~/.config`.
pub fn app_data_root() -> Option<PathBuf> {
    if cfg!(windows) {
        return env_path("APPDATA");
    }
    let home = env_path("HOME")?;
    if cfg!(target_os = "macos") {
        return Some(home.join("Library").join("Application Support"));
    }
    Some(env_path("XDG_CONFIG_HOME").unwrap_or_else(|| home.join(".config")))
}

/// The launcher's own folder: settings, accounts, instance list, caches.
pub fn user_data_dir() -> Option<PathBuf> {
    Some(app_data_root()?.join(APP_DIR))
}

/// The folder holding the game data, including the default instances directory.
pub fn product_dir() -> Option<PathBuf> {
    Some(app_data_root()?.join(PRODUCT_DIR))
}

/// Where instances are created when settings name no other folder.
pub fn default_instances_dir() -> Option<PathBuf> {
    Some(product_dir()?.join("instances"))
}

fn env_path(key: &str) -> Option<PathBuf> {
    match std::env::var_os(key) {
        Some(value) if !value.is_empty() => Some(PathBuf::from(value)),
        _ => None,
    }
}

/// A launcher data directory, so callers can be pointed at a test folder.
#[derive(Debug, Clone)]
pub struct DataDirs {
    user_data: PathBuf,
}

impl DataDirs {
    /// The real folders of the launcher installed on this machine.
    pub fn discover() -> Option<Self> {
        Some(Self { user_data: user_data_dir()? })
    }

    /// A specific folder, for tests and for reading another installation.
    pub fn at(user_data: impl Into<PathBuf>) -> Self {
        Self { user_data: user_data.into() }
    }

    pub fn user_data(&self) -> &Path {
        &self.user_data
    }

    pub fn settings_file(&self) -> PathBuf {
        self.user_data.join("settings.json")
    }

    pub fn instances_file(&self) -> PathBuf {
        self.user_data.join("instances.json")
    }

    /// The Microsoft sign-in file. Electron named it `minecraft-auth.json`.
    pub fn auth_file(&self) -> PathBuf {
        self.user_data.join("minecraft-auth.json")
    }

    pub fn profiles_file(&self) -> PathBuf {
        self.user_data.join("profiles.json")
    }

    pub fn river_session_file(&self) -> PathBuf {
        self.user_data.join("river-session.json")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn data_files_sit_in_the_launcher_folder() {
        let dirs = DataDirs::at("/tmp/river");
        assert!(dirs.settings_file().ends_with("settings.json"));
        assert!(dirs.instances_file().ends_with("instances.json"));
        // Electron's file name, not "auth.json": reading the wrong one would silently
        // present a signed-in player as signed out.
        assert!(dirs.auth_file().ends_with("minecraft-auth.json"));
        assert_eq!(dirs.auth_file().parent(), Some(dirs.user_data()));
    }
}
