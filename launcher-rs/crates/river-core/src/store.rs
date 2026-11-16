//! Reading and writing the launcher's JSON files.
//!
//! Every write goes to a temporary file first and is renamed into place, so a crash or a
//! power cut can never leave a half-written settings or instance list behind. The Electron
//! launcher lost instance lists this way.

use serde::de::DeserializeOwned;
use serde::Serialize;
use std::fs;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{path}: {source}")]
    Io {
        path: String,
        #[source]
        source: std::io::Error,
    },
    #[error("{path} is not valid JSON: {source}")]
    Json {
        path: String,
        #[source]
        source: serde_json::Error,
    },
}

fn io(path: &Path) -> impl Fn(std::io::Error) -> Error + '_ {
    move |source| Error::Io { path: path.display().to_string(), source }
}

/// Reads a JSON file. `Ok(None)` means the file is not there yet.
pub fn read_json<T: DeserializeOwned>(path: &Path) -> Result<Option<T>, Error> {
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(source) if source.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(source) => return Err(Error::Io { path: path.display().to_string(), source }),
    };
    serde_json::from_str(&text)
        .map(Some)
        .map_err(|source| Error::Json { path: path.display().to_string(), source })
}

/// Writes a JSON file in one step, creating the folder if needed.
pub fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<(), Error> {
    let text = serde_json::to_string_pretty(value)
        .map_err(|source| Error::Json { path: path.display().to_string(), source })?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(io(parent))?;
    }
    let temp = path.with_extension(format!("tmp{}", std::process::id()));
    fs::write(&temp, text.as_bytes()).map_err(io(&temp))?;
    match fs::rename(&temp, path) {
        Ok(()) => Ok(()),
        Err(source) => {
            let _ = fs::remove_file(&temp);
            Err(Error::Io { path: path.display().to_string(), source })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_missing_file_reads_as_nothing() {
        let value: Option<serde_json::Value> = read_json(Path::new("no-such-file.json")).unwrap();
        assert!(value.is_none());
    }

    #[test]
    fn writing_creates_the_folder_and_leaves_no_temporary_file() {
        let dir = std::env::temp_dir().join(format!("river-store-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let file = dir.join("nested").join("settings.json");
        write_json(&file, &serde_json::json!({ "memoryMb": 4096 })).unwrap();
        let back: serde_json::Value = read_json(&file).unwrap().unwrap();
        assert_eq!(back["memoryMb"], 4096);
        let leftovers: Vec<_> = fs::read_dir(file.parent().unwrap())
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|name| name.contains("tmp"))
            .collect();
        assert!(leftovers.is_empty(), "left behind {leftovers:?}");
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn broken_json_says_which_file_it_is() {
        let file = std::env::temp_dir().join(format!("river-bad-{}.json", std::process::id()));
        fs::write(&file, b"{ not json").unwrap();
        let failure = read_json::<serde_json::Value>(&file).unwrap_err();
        assert!(failure.to_string().contains("river-bad"));
        fs::remove_file(&file).unwrap();
    }
}
