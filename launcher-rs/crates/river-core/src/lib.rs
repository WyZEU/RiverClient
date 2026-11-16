//! The core of the Rust River launcher: the parts that have no user interface.
//!
//! This is being grown underneath the existing Electron launcher, not beside it. The hard
//! rule is that it reads and writes the same files in the same folders, so a player who
//! updates into the Rust build keeps their accounts, settings and instances without
//! noticing anything happened.

pub mod instances;
pub mod paths;
pub mod settings;
pub mod store;
pub mod update;

pub use instances::{Instance, InstanceList};
pub use paths::DataDirs;
pub use settings::Settings;

/// Everything the launcher knows after reading its files, with nothing fetched yet.
#[derive(Debug, Clone)]
pub struct Launcher {
    pub dirs: DataDirs,
    pub settings: Settings,
    pub instances: InstanceList,
}

impl Launcher {
    /// Loads the launcher state from a data folder.
    pub fn load(dirs: DataDirs) -> Result<Self, store::Error> {
        let settings = settings::read(&dirs.settings_file())?;
        let instances = instances::read(&dirs.instances_file())?;
        Ok(Self { dirs, settings, instances })
    }

    /// Loads the state of the launcher installed on this machine.
    pub fn load_installed() -> Result<Option<Self>, store::Error> {
        match DataDirs::discover() {
            Some(dirs) => Self::load(dirs).map(Some),
            None => Ok(None),
        }
    }

    /// The instance the player last selected, when it is still in the list.
    pub fn selected_instance(&self) -> Option<&Instance> {
        let path = self.settings.selected_instance_path()?;
        self.instances.by_path(&path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_empty_folder_loads_as_a_fresh_install() {
        let dirs = DataDirs::at(std::env::temp_dir().join("river-core-empty"));
        let launcher = Launcher::load(dirs).unwrap();
        assert!(launcher.instances.instances.is_empty());
        assert!(launcher.selected_instance().is_none());
    }

    #[test]
    fn the_selected_instance_comes_from_settings() {
        let dir = std::env::temp_dir().join(format!("river-core-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let dirs = DataDirs::at(&dir);
        let instance_dir = dir.join("instances").join("river-1.21.11");
        std::fs::create_dir_all(&instance_dir).unwrap();
        let instance_path = instance_dir.to_string_lossy().into_owned();
        store::write_json(
            &dirs.instances_file(),
            &serde_json::json!({ "instances": [{
                "id": "river-1.21.11", "name": "Main", "version": "1.21.11",
                "loader": "fabric", "river": true, "path": instance_path
            }] }),
        )
        .unwrap();
        store::write_json(
            &dirs.settings_file(),
            &serde_json::json!({ "memoryMb": 8192, "instancePath": instance_path, "selectedVersion": "1.21.11" }),
        )
        .unwrap();

        let launcher = Launcher::load(dirs).unwrap();
        let selected = launcher.selected_instance().expect("the selected instance is in the list");
        assert_eq!(selected.name, "Main");
        assert!(selected.exists());
        assert_eq!(launcher.settings.memory_mb, 8192);
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
