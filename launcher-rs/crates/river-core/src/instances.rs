//! The player's instances, as the Electron launcher stores them in `instances.json`.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use crate::store;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Instance {
    pub id: String,
    pub name: String,
    /// The Minecraft version this instance runs.
    pub version: String,
    /// `fabric`, `forge`, `neoforge` or `vanilla`.
    #[serde(default)]
    pub loader: String,
    #[serde(default)]
    pub loader_version: String,
    /// Whether River's own mod is installed into this instance.
    #[serde(default)]
    pub river: bool,
    pub path: String,
    #[serde(flatten)]
    pub rest: BTreeMap<String, serde_json::Value>,
}

impl Instance {
    pub fn dir(&self) -> PathBuf {
        PathBuf::from(&self.path)
    }

    /// Whether the folder this instance points at still exists.
    pub fn exists(&self) -> bool {
        self.dir().is_dir()
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct InstanceList {
    #[serde(default)]
    pub instances: Vec<Instance>,
    #[serde(flatten)]
    pub rest: BTreeMap<String, serde_json::Value>,
}

impl InstanceList {
    /// The instance whose folder matches, which is how settings names the selected one.
    pub fn by_path(&self, path: &Path) -> Option<&Instance> {
        self.instances.iter().find(|instance| same_path(&instance.dir(), path))
    }
}

/// Compares two folders the way the launcher must: Windows paths differ in case and in
/// trailing separators without meaning anything different.
fn same_path(left: &Path, right: &Path) -> bool {
    let normalize = |path: &Path| {
        let text = path.to_string_lossy().replace('\\', "/");
        let trimmed = text.trim_end_matches('/').to_string();
        if cfg!(windows) { trimmed.to_lowercase() } else { trimmed }
    };
    normalize(left) == normalize(right)
}

pub fn read(path: &Path) -> Result<InstanceList, store::Error> {
    Ok(store::read_json(path)?.unwrap_or_default())
}

pub fn write(path: &Path, list: &InstanceList) -> Result<(), store::Error> {
    store::write_json(path, list)
}

#[cfg(test)]
mod tests {
    use super::*;

    const REAL: &str = r#"{"instances":[{
        "id":"river-1.21.11-1792276382396","name":"Main","type":"custom","version":"1.21.11",
        "loader":"fabric","loaderVersion":"","river":true,
        "path":"C:\\Users\\x\\AppData\\Roaming\\River Client\\instances\\river-1.21.11-1792276382396",
        "createdAt":"2026-10-17T22:33:02.395Z","updatedAt":"2026-10-17T22:57:35.658Z"}]}"#;

    #[test]
    fn reads_an_instance_the_launcher_wrote() {
        let list: InstanceList = serde_json::from_str(REAL).unwrap();
        let instance = &list.instances[0];
        assert_eq!(instance.name, "Main");
        assert_eq!(instance.version, "1.21.11");
        assert_eq!(instance.loader, "fabric");
        assert!(instance.river);
        // Fields the core does not name must survive a round trip.
        assert!(instance.rest.contains_key("createdAt"));
        assert_eq!(serde_json::from_str::<InstanceList>(&serde_json::to_string(&list).unwrap()).unwrap(), list);
    }

    #[test]
    fn finds_the_selected_instance_by_its_folder() {
        let list: InstanceList = serde_json::from_str(REAL).unwrap();
        let selected = Path::new("C:\\Users\\x\\AppData\\Roaming\\River Client\\instances\\river-1.21.11-1792276382396");
        assert_eq!(list.by_path(selected).unwrap().name, "Main");
    }

    #[test]
    #[cfg(windows)]
    fn a_differently_written_windows_path_is_the_same_instance() {
        let list: InstanceList = serde_json::from_str(REAL).unwrap();
        let selected = Path::new("c:/users/x/appdata/roaming/river client/instances/river-1.21.11-1792276382396/");
        assert!(list.by_path(selected).is_some());
    }

    #[test]
    fn no_file_means_no_instances() {
        assert!(read(Path::new("missing.json")).unwrap().instances.is_empty());
    }
}
