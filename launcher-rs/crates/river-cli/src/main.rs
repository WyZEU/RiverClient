//! A small command-line front end for the core, used to check that the Rust launcher
//! reads exactly what the Electron launcher wrote. It only reads.
//!
//!   river-cli status             what the installed launcher's files say
//!   river-cli verify <file.json> whether an update manifest is signed by River

use anyhow::{bail, Context, Result};
use river_core::{update, DataDirs, Launcher};

fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    match args.next().as_deref() {
        Some("status") | None => status(args.next()),
        Some("verify") => verify(args.next()),
        Some(other) => bail!("unknown command {other:?}; try: status, verify <file.json>"),
    }
}

fn status(data_dir: Option<String>) -> Result<()> {
    let dirs = match data_dir {
        Some(path) => DataDirs::at(path),
        None => DataDirs::discover().context("this system has no application-data folder")?,
    };
    println!("data folder: {}", dirs.user_data().display());
    let launcher = Launcher::load(dirs)?;
    println!(
        "memory: {} MB, selected version: {}",
        launcher.settings.memory_mb,
        blank(&launcher.settings.selected_version)
    );
    println!("instances: {}", launcher.instances.instances.len());
    for instance in &launcher.instances.instances {
        println!(
            "  {} {} {}{} {}{}",
            if launcher.selected_instance().map(|s| &s.id) == Some(&instance.id) { "*" } else { " " },
            instance.name,
            instance.version,
            if instance.loader.is_empty() { String::new() } else { format!(" {}", instance.loader) },
            if instance.river { "with River" } else { "plain" },
            if instance.exists() { "" } else { "  (folder missing)" },
        );
    }
    Ok(())
}

fn verify(file: Option<String>) -> Result<()> {
    let file = file.context("usage: river-cli verify <manifest.json>")?;
    let text = std::fs::read_to_string(&file).with_context(|| format!("reading {file}"))?;
    let manifest: serde_json::Value = serde_json::from_str(&text).with_context(|| format!("parsing {file}"))?;
    match update::verify(&manifest) {
        Ok(()) => {
            println!(
                "signed by River: version {}, minimum {}",
                manifest.get("version").and_then(|v| v.as_str()).unwrap_or("?"),
                manifest.get("minimumVersion").and_then(|v| v.as_str()).unwrap_or("?")
            );
            Ok(())
        }
        Err(failure) => bail!("{file}: {failure}"),
    }
}

fn blank(value: &str) -> &str {
    if value.is_empty() { "(none)" } else { value }
}
