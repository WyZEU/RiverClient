const els = {
  topStatus: document.querySelector("#top-status"),
  railAccountName: document.querySelector("#rail-account-name"),
  railAccountDetail: document.querySelector("#rail-account-detail"),
  railLoginButton: document.querySelector("#rail-login-button"),
  subline: document.querySelector("#subline"),
  pageTitle: document.querySelector("#page-title"),
  heroImage: document.querySelector("#hero-image"),
  heroVersion: document.querySelector("#hero-version"),
  heroCopy: document.querySelector("#hero-copy"),
  homeInstanceList: document.querySelector("#home-instance-list"),
  instanceGrid: document.querySelector("#instance-grid"),
  instanceDetailType: document.querySelector("#instance-detail-type"),
  instanceDetailName: document.querySelector("#instance-detail-name"),
  instanceDetailPath: document.querySelector("#instance-detail-path"),
  instanceModList: document.querySelector("#instance-mod-list"),
  resourcepackList: document.querySelector("#resourcepack-list"),
  shaderpackList: document.querySelector("#shaderpack-list"),
  launcherUpdateState: document.querySelector("#launcher-update-state"),
  crashSummary: document.querySelector("#crash-summary"),
  moduleGrid: document.querySelector("#module-grid"),
  log: document.querySelector("#log"),
  memoryInput: document.querySelector("#memory-input"),
  memoryLabel: document.querySelector("#memory-label"),
  parallelInput: document.querySelector("#parallel-input"),
  widthInput: document.querySelector("#width-input"),
  heightInput: document.querySelector("#height-input"),
  offlineNameInput: document.querySelector("#offline-name-input"),
  pvpVersionInput: document.querySelector("#pvp-version-input"),
  javaPathInput: document.querySelector("#java-path-input"),
  densityInput: document.querySelector("#density-input"),
  keepOpenInput: document.querySelector("#keep-open-input"),
  devOfflineInput: document.querySelector("#dev-offline-input"),
  verifyDownloadsInput: document.querySelector("#verify-downloads-input"),
  consoleInput: document.querySelector("#console-input"),
  telemetryInput: document.querySelector("#telemetry-input"),
  curseForgeKeyInput: document.querySelector("#curseforge-key-input"),
  modSource: document.querySelector("#mod-source"),
  modQuery: document.querySelector("#mod-query"),
  modVersion: document.querySelector("#mod-version"),
  modLoader: document.querySelector("#mod-loader"),
  modTags: document.querySelector("#mod-tags"),
  modResults: document.querySelector("#mod-results"),
  modHint: document.querySelector("#mod-hint"),
  conflictList: document.querySelector("#conflict-list"),
  installedMods: document.querySelector("#installed-mods"),
  installedCount: document.querySelector("#installed-count"),
  networkBanner: document.querySelector("#network-banner"),
  authOverlay: document.querySelector("#auth-overlay"),
  authUserCode: document.querySelector("#auth-user-code"),
  authDetail: document.querySelector("#auth-detail"),
  authOpenBrowser: document.querySelector("#auth-open-browser"),
  authClose: document.querySelector("#auth-close"),
  activityPanel: document.querySelector("#activity-panel"),
  activityTitle: document.querySelector("#activity-title"),
  activityDetail: document.querySelector("#activity-detail"),
  activityProgress: document.querySelector("#activity-progress"),
  activityCount: document.querySelector("#activity-count"),
  bootScreen: document.querySelector("#boot-screen"),
  bootTitle: document.querySelector("#boot-title"),
  bootDetail: document.querySelector("#boot-detail"),
  toastStack: document.querySelector("#toast-stack")
};

let currentStatus = null;
let saveTimer = null;
let authVerificationUri = "";
let authClearTimer = null;
let activityClearTimer = null;

function appendLog(line) {
  els.log.textContent += line;
  if (!line.endsWith("\n")) els.log.textContent += "\n";
  els.log.scrollTop = els.log.scrollHeight;
}

function renderStatus(status) {
  currentStatus = status;
  const settings = status.settings;
  const selectedVersion = status.versions.find((version) => version.selected) || status.versions[0];

  document.body.dataset.density = settings.uiDensity;
  const statusText = status.setupRunning ? "Setting up" : status.running ? "Launching" : status.jarReady ? "Ready" : "Needs build";
  els.topStatus.textContent = statusText;
  els.subline.textContent = status.mode;
  els.heroVersion.textContent = "River Client";
  els.heroCopy.textContent = `${settings.selectedVersion} Fabric`;
  els.heroImage.style.backgroundImage = `linear-gradient(90deg, rgba(0,0,0,.78), rgba(0,0,0,.22)), url("${selectedVersion.image}")`;

  els.memoryInput.value = settings.memoryMb;
  els.memoryLabel.textContent = `${settings.memoryMb} MB`;
  els.parallelInput.value = settings.maxParallelDownloads;
  els.widthInput.value = settings.resolution.width;
  els.heightInput.value = settings.resolution.height;
  els.offlineNameInput.value = settings.offlineName;
  els.pvpVersionInput.value = settings.selectedVersion;
  els.javaPathInput.value = settings.javaPath;
  els.densityInput.value = settings.uiDensity;
  els.keepOpenInput.checked = settings.keepLauncherOpen;
  els.devOfflineInput.checked = settings.developerOfflineMode;
  els.verifyDownloadsInput.checked = settings.verifyModDownloads;
  els.consoleInput.checked = settings.showConsoleOnLaunch;
  els.telemetryInput.checked = settings.telemetry;
  els.curseForgeKeyInput.value = settings.curseForgeApiKey || "";
  els.modSource.value = settings.modFilters.source;
  els.modVersion.value = settings.modFilters.version;
  els.modLoader.value = settings.modFilters.loader;
  els.modTags.value = settings.modFilters.tags;

  const accountName = status.auth && status.auth.profile ? status.auth.profile.name : "Not signed in";
  els.railAccountName.textContent = accountName;
  els.railAccountDetail.textContent = status.authReady ? "Online" : settings.developerOfflineMode ? "Offline test" : "Login";
  els.railLoginButton.title = status.authReady ? "Logout" : "Login";
  renderNetwork(status.network);

  document.querySelector("#play-button").disabled = status.running || status.setupRunning || !status.jarReady || (!status.authReady && !settings.developerOfflineMode);
  document.querySelector("#stop-button").disabled = !status.running;

  renderInstances(status.instances || []);
  renderLocalFiles(els.resourcepackList, status.packs || [], "No resource packs in this instance.");
  renderLocalFiles(els.shaderpackList, status.shaders || [], "No shader packs in this instance.");
  els.launcherUpdateState.textContent = status.launcherUpdate?.message || "Not checked yet.";
  els.crashSummary.textContent = status.crashInfo?.summary || "No crash reports found.";
  renderModules(status.modules);
  renderInstalledMods(status.installedMods);
  renderConflicts(status.installedMods);
}

function renderNetwork(network) {
  const offline = network && network.online === false && !network.checking;
  els.networkBanner.classList.toggle("hidden", !offline);
  if (offline) {
    els.networkBanner.querySelector("span").textContent = network.message || "Online features are paused until the network comes back.";
  }
}

function renderInstances(instances) {
  if (!instances.length) {
    els.instanceGrid.innerHTML = `<article class="empty-card">No instances yet.</article>`;
    els.homeInstanceList.innerHTML = `<article class="empty-card">No instances yet.</article>`;
    return;
  }

  const selected = instances.find((instance) => instance.selected) || instances[0];
  renderInstanceDetail(selected);
  const rows = instances.map((instance) => {
    const card = document.createElement("article");
    card.className = `instance-row${instance.selected ? " selected" : ""}`;
    card.innerHTML = `
      <div>
        <strong>${escapeHtml(instance.name)}</strong>
        <small>${escapeHtml(instance.version)} / ${escapeHtml(instance.loader)}</small>
      </div>
      <button data-action="select" ${instance.selected ? "disabled" : ""}>${instance.selected ? "Selected" : "Open"}</button>
    `;
    card.querySelector('[data-action="select"]').addEventListener("click", async () => {
      showToast("Selecting instance", instance.name);
      const result = await window.clientcore.selectInstance(instance.id);
      showToast(result.ok ? "Instance selected" : "Instance failed", result.message);
      await refresh();
    });
    card.addEventListener("click", (event) => {
      if (event.target.closest("button")) return;
      renderInstanceDetail(instance);
      switchView("instances", "Instances");
    });
    return card;
  });

  els.instanceGrid.replaceChildren(...rows);
  els.homeInstanceList.replaceChildren(...instances.slice(0, 4).map((instance) => {
    const row = document.createElement("article");
    row.className = `instance-row${instance.selected ? " selected" : ""}`;
    row.innerHTML = `<div><strong>${escapeHtml(instance.name)}</strong><small>${escapeHtml(instance.version)} / ${escapeHtml(instance.loader)}</small></div><button>Open</button>`;
    row.addEventListener("click", async () => {
      if (!instance.selected) await window.clientcore.selectInstance(instance.id);
      switchView("instances", "Instances");
      await refresh();
    });
    return row;
  }));
}

function renderInstanceDetail(instance) {
  els.instanceDetailType.textContent = `${instance.loader} / ${instance.version}`;
  els.instanceDetailName.textContent = instance.name;
  els.instanceDetailPath.textContent = instance.path;
  renderInstanceMods(currentStatus?.installedMods || []);
}

function renderInstanceMods(mods) {
  if (!mods.length) {
    els.instanceModList.innerHTML = `<p class="empty">No mods installed.</p>`;
    return;
  }
  els.instanceModList.replaceChildren(...mods.map((mod) => {
    const metadata = mod.metadata || {};
    const row = document.createElement("div");
    row.className = "file-row";
    row.innerHTML = `
      <div>
        <strong>${escapeHtml(metadata.title || mod.file)}</strong>
        <small>${escapeHtml(metadata.versionNumber || mod.file)}</small>
      </div>
      <button data-action="toggle">${mod.disabled ? "Enable" : "Disable"}</button>
      <button data-action="remove">Remove</button>
    `;
    row.querySelector('[data-action="toggle"]').addEventListener("click", async () => {
      const result = await window.clientcore.setModEnabled(mod.file, Boolean(mod.disabled));
      showToast(result.ok ? "Mod updated" : "Mod failed", result.message);
      await refresh();
    });
    row.querySelector('[data-action="remove"]').addEventListener("click", async () => {
      if (!window.confirm(`Remove ${mod.file}?`)) return;
      const result = await window.clientcore.removeMod(mod.file);
      showToast(result.ok ? "Mod removed" : "Mod failed", result.message);
      await refresh();
    });
    return row;
  }));
}

function renderLocalFiles(container, files, emptyText) {
  if (!container) return;
  if (!files.length) {
    container.innerHTML = `<p class="empty">${escapeHtml(emptyText)}</p>`;
    return;
  }
  container.replaceChildren(...files.map((file) => {
    const item = document.createElement("div");
    item.className = "file-chip";
    item.textContent = file.file;
    return item;
  }));
}

function renderModules(modules) {
  els.moduleGrid.replaceChildren(...modules.map((module) => {
    const card = document.createElement("article");
    card.className = "module-card";
    card.innerHTML = `
      <span>${escapeHtml(module.category)}</span>
      <h3>${escapeHtml(module.name)}</h3>
      <p>${module.enabled ? "Enabled in v1 config" : "Disabled"}</p>
    `;
    return card;
  }));
}

function renderInstalledMods(mods) {
  els.installedCount.textContent = `${mods.length} jar${mods.length === 1 ? "" : "s"}`;
  if (!mods.length) {
    els.installedMods.innerHTML = `<p class="empty">No extra mods installed yet.</p>`;
    return;
  }

  els.installedMods.replaceChildren(...mods.map((mod) => {
    const item = document.createElement("div");
    item.className = "installed-mod";
    const metadata = mod.metadata || {};
    const update = mod.update;
    const title = metadata.title || mod.file;
    const version = metadata.versionNumber ? ` ${metadata.versionNumber}` : "";
    const badge = update && update.available ? `<strong>Update ${escapeHtml(update.latestVersionNumber || "")}</strong>` : "";
    const conflictBadge = mod.conflicts && mod.conflicts.length ? `<strong class="danger">Conflict</strong>` : "";
    const disabled = mod.disabled ? `<strong class="danger">Disabled</strong>` : "";
    item.innerHTML = `
      <span>${escapeHtml(title)}${escapeHtml(version)}</span>${badge}${conflictBadge}${disabled}
      <button data-action="toggle">${mod.disabled ? "Enable" : "Disable"}</button>
      <button data-action="remove">Remove</button>
    `;
    item.querySelector('[data-action="toggle"]').addEventListener("click", async () => {
      const result = await window.clientcore.setModEnabled(mod.file, Boolean(mod.disabled));
      showToast(result.ok ? "Mod updated" : "Mod failed", result.message);
      await refresh();
    });
    item.querySelector('[data-action="remove"]').addEventListener("click", async () => {
      if (!window.confirm(`Remove ${mod.file}?`)) return;
      const result = await window.clientcore.removeMod(mod.file);
      showToast(result.ok ? "Mod removed" : "Mod failed", result.message);
      await refresh();
    });
    return item;
  }));
}

function renderConflicts(mods) {
  const conflicts = [];
  mods.forEach((mod) => {
    (mod.conflicts || []).forEach((conflict) => {
      conflicts.push(`${mod.metadata?.title || mod.file} is incompatible with ${conflict.installedTitle || conflict.title}`);
    });
  });

  if (!conflicts.length) {
    els.conflictList.innerHTML = "";
    return;
  }

  els.conflictList.replaceChildren(...conflicts.map((conflict) => {
    const item = document.createElement("article");
    item.className = "conflict-card";
    item.textContent = conflict;
    return item;
  }));
}

function renderModResults(payload) {
  if (!payload.ok) {
    els.modResults.innerHTML = `<article class="empty-card">${escapeHtml(payload.message || "Search failed.")}</article>`;
    return;
  }

  if (payload.requiresKey) {
    els.modResults.innerHTML = `<article class="empty-card">CurseForge API search needs an API key. Open the official filtered search instead, or paste a key in Settings.</article>`;
    return;
  }

  const results = payload.results || [];
  if (!results.length) {
    els.modResults.innerHTML = `<article class="empty-card">No mods matched those filters.</article>`;
    return;
  }

  els.modResults.replaceChildren(...results.map((mod) => {
    const card = document.createElement("article");
    card.className = "mod-card";
    const title = escapeHtml(mod.title || "Unknown");
    const description = escapeHtml(mod.description || "");
    const author = escapeHtml(mod.author || "Unknown");
    const iconUrl = escapeHtml(mod.iconUrl || "");
    const image = iconUrl ? `<img src="${iconUrl}" alt="" />` : `<div class="mod-icon">${title.slice(0, 1)}</div>`;
    const action = mod.source === "modrinth" ? "Download" : "Open";
    const conflict = mod.conflict ? `<small class="danger-text">${escapeHtml(mod.conflict.message)}</small>` : "";
    card.innerHTML = `
      ${image}
      <div>
        <span>${escapeHtml(mod.source)} / ${escapeHtml(mod.loader)} / ${escapeHtml(mod.gameVersion)}</span>
        <h3>${title}</h3>
        <p>${description}</p>
        <small>${Number(mod.downloads || 0).toLocaleString()} downloads / ${author}</small>
        ${conflict}
      </div>
      <div class="mod-actions">
        <button data-action="open"><svg><use href="#i-external"></use></svg><span>Open</span></button>
        <button class="primary" data-action="download"><svg><use href="${mod.source === "modrinth" ? "#i-download" : "#i-external"}"></use></svg><span>${action}</span></button>
      </div>
    `;

    card.querySelector('[data-action="open"]').addEventListener("click", () => {
      showToast("Opening page", mod.title || "Mod page");
      window.clientcore.openExternal(mod.url);
    });
    card.querySelector('[data-action="download"]').addEventListener("click", async () => {
      if (mod.source !== "modrinth") {
        showToast("Opening CurseForge", mod.title || "Mod page");
        await window.clientcore.openExternal(mod.url);
        return;
      }
      showToast("Download started", mod.title || "Mod");
      const result = await window.clientcore.downloadMod(mod);
      showToast(result.ok ? "Download complete" : "Download failed", result.message);
      appendLog(`[mods] ${result.message}`);
      await refresh();
    });

    return card;
  }));
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

async function refresh() {
  renderStatus(await window.clientcore.getStatus());
}

async function runAction(buttonId, action) {
  const button = document.querySelector(buttonId);
  button.disabled = true;
  const result = await action();
  showToast(result.ok ? "Action complete" : "Action failed", result.message);
  appendLog(`[launcher] ${result.message}`);
  await refresh();
}

function scheduleSettingsSave() {
  window.clearTimeout(saveTimer);
  saveTimer = window.setTimeout(async () => {
    const version = currentStatus?.settings?.selectedVersion || "1.21.11";
    await window.clientcore.updateSettings({
      memoryMb: Number(els.memoryInput.value),
      maxParallelDownloads: Number(els.parallelInput.value),
      resolution: {
        width: Number(els.widthInput.value),
        height: Number(els.heightInput.value)
      },
      offlineName: els.offlineNameInput.value,
      javaPath: els.javaPathInput.value,
      uiDensity: els.densityInput.value,
      keepLauncherOpen: els.keepOpenInput.checked,
      developerOfflineMode: els.devOfflineInput.checked,
      autoBuildBeforeLaunch: true,
      autoInstallAfterBuild: true,
      verifyModDownloads: els.verifyDownloadsInput.checked,
      showConsoleOnLaunch: els.consoleInput.checked,
      telemetry: els.telemetryInput.checked,
      curseForgeApiKey: els.curseForgeKeyInput.value,
      modFilters: {
        source: els.modSource.value,
        version: els.modVersion.value || version,
        loader: els.modLoader.value,
        tags: els.modTags.value
      }
    });
    await refresh();
  }, 250);
}

function switchView(viewId, title) {
  document.querySelectorAll(".nav").forEach((nav) => nav.classList.toggle("active", nav.dataset.view === viewId));
  document.querySelectorAll(".view").forEach((view) => view.classList.toggle("active", view.id === viewId));
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.tabTarget === viewId || (!tab.dataset.tabTarget && viewId === "home"));
  });
  els.pageTitle.textContent = title || document.querySelector(`[data-view="${viewId}"]`)?.dataset.title || "Home";
}

document.querySelectorAll(".nav").forEach((button) => {
  button.addEventListener("click", () => switchView(button.dataset.view, button.dataset.title));
});

document.querySelectorAll("[data-tab-target]").forEach((button) => {
  button.addEventListener("click", () => {
    const target = button.dataset.tabTarget;
    switchView(target, document.querySelector(`[data-view="${target}"]`)?.dataset.title);
  });
});

document.querySelectorAll("[data-window]").forEach((button) => {
  button.addEventListener("click", () => window.clientcore.windowAction(button.dataset.window));
});

document.querySelector("#play-button").addEventListener("click", () => runAction("#play-button", window.clientcore.launchClient));
document.querySelector("#instance-detail-play").addEventListener("click", () => runAction("#instance-detail-play", window.clientcore.launchClient));
document.querySelector("#stop-button").addEventListener("click", () => runAction("#stop-button", window.clientcore.stopClient));
document.querySelector("#root-folder").addEventListener("click", () => openFolder("root", "Opening project folder"));
document.querySelector("#instance-folder").addEventListener("click", () => openFolder("instance", "Opening instance folder"));
document.querySelector("#instance-detail-folder").addEventListener("click", () => openFolder("instance", "Opening instance folder"));
document.querySelector("#logs-folder").addEventListener("click", () => openFolder("logs", "Opening logs folder"));
document.querySelector("#crashes-folder").addEventListener("click", () => openFolder("crashes", "Opening crash reports"));
document.querySelector("#open-mods-folder").addEventListener("click", () => openFolder("instance", "Opening mods folder"));
document.querySelector("#open-resourcepacks-folder").addEventListener("click", () => openFolder("resourcepacks", "Opening resource packs"));
document.querySelector("#open-shaderpacks-folder").addEventListener("click", () => openFolder("shaderpacks", "Opening shader packs"));
document.querySelector("#clear-log").addEventListener("click", () => {
  els.log.textContent = "";
});
document.querySelectorAll("[data-instance-panel]").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll("[data-instance-panel]").forEach((item) => item.classList.toggle("active", item === button));
    document.querySelectorAll(".instance-panel").forEach((panel) => panel.classList.toggle("active", panel.id === button.dataset.instancePanel));
  });
});
document.querySelector("#create-pvp-instance").addEventListener("click", async () => {
  const version = (els.pvpVersionInput.value || currentStatus?.settings?.selectedVersion || "1.21.11").trim();
  if (!version) {
    showToast("PvP setup failed", "Choose a Minecraft version first.");
    return;
  }
  showToast("Creating River PvP", `${version} Fabric preset`);
  appendLog(`[preset] Creating River PvP ${version}...`);
  const result = await window.clientcore.createPresetInstance({ presetId: "pvp", version });
  showToast(result.ok ? "PvP instance ready" : "PvP setup failed", result.message);
  appendLog(`[preset] ${result.message}`);
  if (result.skipped && result.skipped.length) appendLog(`[preset] Skipped: ${result.skipped.join(", ")}`);
  await refresh();
});
document.querySelector("#create-instance-button").addEventListener("click", async () => {
  const version = (els.pvpVersionInput.value || currentStatus?.settings?.selectedVersion || "1.21.11").trim();
  showToast("Instance version selected", `${version} Fabric`);
  await window.clientcore.selectVersion(version);
  await refresh();
});
document.querySelector("#open-source-site").addEventListener("click", () => {
  const source = els.modSource.value;
  const query = encodeURIComponent(els.modQuery.value || "");
  const version = encodeURIComponent(els.modVersion.value || "1.21.11");
  const url = source === "curseforge"
    ? `https://www.curseforge.com/minecraft/search?class=mc-mods&search=${query}&version=${version}`
    : `https://modrinth.com/mods?q=${query}&v=${version}&l=${encodeURIComponent(els.modLoader.value)}`;
  showToast("Opening browser", source === "curseforge" ? "CurseForge search" : "Modrinth search");
  window.clientcore.openExternal(url);
});
document.querySelector("#import-profile-button").addEventListener("click", async () => {
  renderActivity({ title: "Importing profile", detail: "Choose a Modrinth .mrpack or JSON export...", current: 0, total: 0 });
  const result = await window.clientcore.importModrinthProfile();
  showToast(result.ok ? "Profile imported" : "Import failed", result.message);
  appendLog(`[mods] ${result.message}`);
  if (result.skipped && result.skipped.length) {
    appendLog(`[mods] Skipped incompatible: ${result.skipped.join(", ")}`);
  }
  await refresh();
});
document.querySelector("#check-updates-button").addEventListener("click", async () => {
  showToast("Checking updates", "Looking through installed Modrinth mods");
  const result = await window.clientcore.checkModUpdates();
  showToast(result.ok ? "Update check complete" : "Update check failed", result.message);
  appendLog(`[mods] ${result.message}`);
  await refresh();
});
document.querySelector("#update-all-button").addEventListener("click", async () => {
  showToast("Updating mods", "Installing available updates and dependencies");
  const result = await window.clientcore.updateAllMods();
  showToast(result.ok ? "Update all complete" : "Update all failed", result.message);
  appendLog(`[mods] ${result.message}`);
  await refresh();
});
document.querySelector("#sync-client-settings")?.addEventListener("click", async () => {
  const result = await window.clientcore.syncClientSettings();
  showToast(result.ok ? "Settings synced" : "Sync failed", result.message);
  appendLog(`[settings] ${result.message}`);
  await refresh();
});
els.railLoginButton.addEventListener("click", async () => {
  if (currentStatus?.authReady) {
    const result = await window.clientcore.microsoftLogout();
    showToast("Microsoft account", result.message);
    await refresh();
    return;
  }
  showToast("Microsoft login", "Opening secure sign-in");
  const result = await window.clientcore.microsoftLogin();
  showToast(result.ok ? "Signed in" : "Login failed", result.message);
  clearAuthCode();
  await refresh();
});
document.querySelector("#mod-search-button").addEventListener("click", async () => {
  showToast("Searching mods", `${els.modVersion.value || "1.21.11"} ${els.modLoader.value}`);
  els.modResults.innerHTML = `<article class="empty-card">Searching...</article>`;
  const payload = await window.clientcore.searchMods({
    source: els.modSource.value,
    query: els.modQuery.value,
    version: els.modVersion.value,
    loader: els.modLoader.value,
    tags: els.modTags.value
  });
  if (payload.url && payload.requiresKey) {
    els.modHint.innerHTML = `CurseForge needs an API key for in-app API results. <button id="curse-open-inline">Open CurseForge search</button>`;
    document.querySelector("#curse-open-inline").addEventListener("click", () => window.clientcore.openExternal(payload.url));
  } else {
    els.modHint.textContent = payload.source === "modrinth"
      ? "Modrinth results can be downloaded directly into the managed instance mods folder."
      : "CurseForge API results are available because an API key is configured.";
  }
  renderModResults(payload);
  showToast("Search finished", `${(payload.results || []).length} result${(payload.results || []).length === 1 ? "" : "s"}`);
});

[
  els.memoryInput,
  els.parallelInput,
  els.widthInput,
  els.heightInput,
  els.offlineNameInput,
  els.javaPathInput,
  els.densityInput,
  els.keepOpenInput,
  els.verifyDownloadsInput,
  els.consoleInput,
  els.telemetryInput,
  els.curseForgeKeyInput,
  els.modSource,
  els.modVersion,
  els.modLoader,
  els.modTags,
  els.devOfflineInput
].forEach((input) => {
  input.addEventListener("input", () => {
    els.memoryLabel.textContent = `${els.memoryInput.value} MB`;
    scheduleSettingsSave();
  });
  input.addEventListener("change", () => {
    els.memoryLabel.textContent = `${els.memoryInput.value} MB`;
    scheduleSettingsSave();
  });
});

window.clientcore.onLog(appendLog);
window.clientcore.onStatus(renderStatus);
window.clientcore.onBoot((state) => {
  els.bootTitle.textContent = state.step;
  els.bootDetail.textContent = state.detail;
  appendLog(`[boot] ${state.step}: ${state.detail}`);
  if (state.done) {
    showToast(state.error ? "Startup issue" : "Ready", state.detail);
    window.setTimeout(() => els.bootScreen.classList.add("hidden"), state.error ? 1800 : 700);
  }
});
window.clientcore.onActivity(renderActivity);
window.clientcore.onAuth((state) => {
  if (state.type === "device-code") {
    showAuthCode(state.userCode || "", state.verificationUri || "");
    setAuthDetail("Use the Microsoft page that opened in your browser. This code is temporary and will be cleared after login.");
  }
  if (state.type === "stage") {
    setAuthDetail(state.detail || state.title || "Continuing sign-in...");
  }
  if (state.type === "error") {
    setAuthDetail(state.detail || "Login failed.");
  }
  if (state.type === "done") {
    setAuthDetail(state.detail || "Signed in.");
  }
});

els.authOpenBrowser.addEventListener("click", () => {
  if (authVerificationUri) window.clientcore.openExternal(authVerificationUri);
});

els.authClose.addEventListener("click", clearAuthCode);

refresh().then(() => {
  window.clientcore.refreshVersions().then(refresh);
  window.clientcore.checkNetwork().then(() => refresh());
  if (currentStatus?.root) {
    appendLog(`[launcher] Project: ${currentStatus.root}`);
    appendLog(`[launcher] Instance: ${currentStatus.instancePath}`);
  } else {
    appendLog("[launcher] Running in standalone mode with bundled Riv3r Client jar.");
  }
});

window.setInterval(async () => {
  try {
    await window.clientcore.checkNetwork();
    await refresh();
  } catch {
    await refresh();
  }
}, 2000);

function renderActivity(state) {
  els.activityTitle.textContent = state.title || "Working";
  els.activityDetail.textContent = state.detail || "Please wait...";
  const total = Number(state.total || 0);
  const current = Number(state.current || 0);
  const percent = total > 0 ? Math.max(4, Math.min(100, Math.round((current / total) * 100))) : 18;
  els.activityProgress.style.width = `${state.done ? 100 : percent}%`;
  els.activityCount.textContent = total > 0 ? `${Math.min(current, total)} / ${total}` : "Working";
  els.activityPanel.classList.toggle("error", Boolean(state.error));
  els.activityPanel.classList.remove("hidden");
  window.clearTimeout(activityClearTimer);
  if (state.done) {
    activityClearTimer = window.setTimeout(() => {
      els.activityPanel.classList.add("hidden");
      els.activityPanel.classList.remove("error");
    }, state.error ? 9000 : 4500);
  }
}

async function openFolder(folder, message) {
  showToast(message, "Opening in Explorer");
  const ok = await window.clientcore.openFolder(folder);
  if (!ok) showToast("Could not open folder", folder);
}

function showToast(title, detail = "") {
  const toast = document.createElement("article");
  toast.className = "toast";
  toast.innerHTML = `<strong>${escapeHtml(title)}</strong><span>${escapeHtml(detail)}</span>`;
  els.toastStack.appendChild(toast);
  window.setTimeout(() => toast.classList.add("leaving"), 2600);
  window.setTimeout(() => toast.remove(), 3200);
}

function showAuthCode(userCode, verificationUri) {
  authVerificationUri = verificationUri;
  els.authUserCode.textContent = userCode || "--------";
  els.authOverlay.classList.remove("hidden");
  els.authOverlay.setAttribute("aria-hidden", "false");
  window.clearTimeout(authClearTimer);
  authClearTimer = window.setTimeout(clearAuthCode, 10 * 60 * 1000);
}

function setAuthDetail(detail) {
  els.authDetail.textContent = detail;
}

function clearAuthCode() {
  authVerificationUri = "";
  els.authUserCode.textContent = "--------";
  setAuthDetail("Use the Microsoft page that opened in your browser. This code is temporary and will be cleared after login.");
  els.authOverlay.classList.add("hidden");
  els.authOverlay.setAttribute("aria-hidden", "true");
  window.clearTimeout(authClearTimer);
}
