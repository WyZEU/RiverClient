//! The launcher's settings file.
//!
//! Only the fields the core actually needs are named; everything else in the file is
//! kept in `rest` and written back untouched, so an older Rust build can never drop a
//! setting a newer Electron build wrote.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::PathBuf;

use crate::store;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    /// Megabytes of memory given to the game.
    #[serde(default = "default_memory")]
    pub memory_mb: u32,
    /// The instance folder the player last selected, empty when none is chosen.
    #[serde(default)]
    pub instance_path: String,
    #[serde(default)]
    pub selected_version: String,
    #[serde(default)]
    pub java_path: String,
    #[serde(default)]
    pub jvm_args: String,
    /// Every other setting, preserved exactly as written.
    #[serde(flatten)]
    pub rest: BTreeMap<String, serde_json::Value>,
}

fn default_memory() -> u32 {
    4096
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            memory_mb: default_memory(),
            instance_path: String::new(),
            selected_version: String::new(),
            java_path: String::new(),
            jvm_args: String::new(),
            rest: BTreeMap::new(),
        }
    }
}

impl Settings {
    /// The selected instance folder, if one is set.
    pub fn selected_instance_path(&self) -> Option<PathBuf> {
        if self.instance_path.is_empty() {
            None
        } else {
            Some(PathBuf::from(&self.instance_path))
        }
    }
}

/// Reads the settings file. A missing file means a fresh install, not an error.
pub fn read(path: &std::path::Path) -> Result<Settings, store::Error> {
    Ok(store::read_json(path)?.unwrap_or_default())
}

/// Writes the settings file, replacing it only once the new contents are safely on disk.
pub fn write(path: &std::path::Path, settings: &Settings) -> Result<(), store::Error> {
    store::write_json(path, settings)
}

#[cfg(test)]
mod tests {
    use super::*;

    const REAL: &str = r#"{
        "selectedProfile": "", "memoryMb": 14336, "resolution": {"width": 1280, "height": 720},
        "closeOnLaunch": false, "telemetry": true,
        "instancePath": "C:\\Users\\x\\AppData\\Roaming\\River Client\\instances\\river-1.21.11-1792276382396",
        "selectedVersion": "1.21.11", "offlineName": "", "javaPath": "", "jvmArgs": ""
    }"#;

    #[test]
    fn reads_the_fields_the_core_needs() {
        let settings: Settings = serde_json::from_str(REAL).unwrap();
        assert_eq!(settings.memory_mb, 14336);
        assert_eq!(settings.selected_version, "1.21.11");
        assert!(settings.selected_instance_path().unwrap().ends_with("river-1.21.11-1792276382396"));
    }

    #[test]
    fn keeps_settings_it_does_not_know_about() {
        let settings: Settings = serde_json::from_str(REAL).unwrap();
        // Dropping these on write would silently reset the player's launcher.
        assert!(settings.rest.contains_key("resolution"));
        assert!(settings.rest.contains_key("telemetry"));
        let written = serde_json::to_string(&settings).unwrap();
        let again: Settings = serde_json::from_str(&written).unwrap();
        assert_eq!(settings, again);
        assert!(written.contains("\"closeOnLaunch\":false"));
    }

    #[test]
    fn a_missing_file_is_a_fresh_install() {
        let settings = read(std::path::Path::new("does-not-exist.json")).unwrap();
        assert_eq!(settings.memory_mb, 4096);
        assert!(settings.selected_instance_path().is_none());
    }
}
