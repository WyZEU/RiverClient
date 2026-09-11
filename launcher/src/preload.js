const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("clientcore", {
  getStatus: () => ipcRenderer.invoke("launcher:get-status"),
  refreshVersions: () => ipcRenderer.invoke("launcher:refresh-versions"),
  checkNetwork: () => ipcRenderer.invoke("launcher:check-network"),
  checkLauncherUpdates: () => ipcRenderer.invoke("launcher:check-launcher-updates"),
  installLauncherUpdate: () => ipcRenderer.invoke("launcher:install-launcher-update"),
  installPublisherCert: () => ipcRenderer.invoke("launcher:install-publisher-cert"),
  refreshAuth: () => ipcRenderer.invoke("launcher:refresh-auth"),
  updateSettings: (patch) => ipcRenderer.invoke("launcher:update-settings", patch),
  selectProfile: (profileId) => ipcRenderer.invoke("launcher:select-profile", profileId),
  selectVersion: (versionId) => ipcRenderer.invoke("launcher:select-version", versionId),
  selectInstance: (instanceId) => ipcRenderer.invoke("launcher:select-instance", instanceId),
  createInstance: (request) => ipcRenderer.invoke("launcher:create-instance", request),
  duplicateInstance: (request) => ipcRenderer.invoke("launcher:duplicate-instance", request),
  createPresetInstance: (request) => ipcRenderer.invoke("launcher:create-preset-instance", request),
  deleteInstance: (instanceId) => ipcRenderer.invoke("launcher:delete-instance", instanceId),
  repairInstance: (request) => ipcRenderer.invoke("launcher:repair-instance", request),
  repairAll: () => ipcRenderer.invoke("launcher:repair-all"),
  reportRendererError: (payload) => ipcRenderer.invoke("launcher:report-renderer-error", payload),
  launchClient: (opts) => ipcRenderer.invoke("launcher:launch-client", opts || {}),
  stopClient: () => ipcRenderer.invoke("launcher:stop-client"),
  searchMods: (request) => ipcRenderer.invoke("launcher:search-mods", request),
  getModrinthProject: (request) => ipcRenderer.invoke("launcher:get-modrinth-project", request),
  downloadMod: (mod) => ipcRenderer.invoke("launcher:download-mod", mod),
  setModEnabled: (request, enabled) => ipcRenderer.invoke("launcher:set-mod-enabled", request, enabled),
  removeMod: (request) => ipcRenderer.invoke("launcher:remove-mod", request),
  removeContent: (request) => ipcRenderer.invoke("launcher:remove-content", request),
  syncClientSettings: () => ipcRenderer.invoke("launcher:sync-client-settings"),
  importModrinthProfile: () => ipcRenderer.invoke("launcher:import-modrinth-profile"),
  importModpackFile: () => ipcRenderer.invoke("launcher:import-modpack-file"),
  detectExternalInstances: () => ipcRenderer.invoke("launcher:detect-external-instances"),
  importExternalInstance: (entry) => ipcRenderer.invoke("launcher:import-external-instance", entry),
  checkModUpdates: (request) => ipcRenderer.invoke("launcher:check-mod-updates", request),
  updateAllMods: (request) => ipcRenderer.invoke("launcher:update-all-mods", request),
  openFolder: (folder) => ipcRenderer.invoke("launcher:open-folder", folder),
  openExternal: (url) => ipcRenderer.invoke("launcher:open-external", url),
  microsoftStatus: () => ipcRenderer.invoke("launcher:microsoft-status"),
  microsoftLogin: () => ipcRenderer.invoke("launcher:microsoft-login"),
  microsoftLogout: () => ipcRenderer.invoke("launcher:microsoft-logout"),
  getSessionHistory: () => ipcRenderer.invoke("launcher:get-session-history"),
  getPlaytimeSummary: () => ipcRenderer.invoke("launcher:get-playtime-summary"),
  setSocialStatus: (status) => ipcRenderer.invoke("launcher:set-social-status", status),
  getPlayerSkin: (name) => ipcRenderer.invoke("launcher:get-player-skin", name),
  riverSocial: (action, payload) => ipcRenderer.invoke("launcher:river-social", { action, payload }),
  getRecentServers: () => ipcRenderer.invoke("launcher:get-recent-servers"),
  getNews: () => ipcRenderer.invoke("launcher:get-news"),
  readCrashReport: (filePath) => ipcRenderer.invoke("launcher:read-crash-report", filePath),
  uploadCrashReport: (options) => ipcRenderer.invoke("launcher:upload-crash-report", options),
  getLogFiles: () => ipcRenderer.invoke("launcher:get-log-files"),
  resetSettings: () => ipcRenderer.invoke("launcher:reset-settings"),
  getStorageInfo: () => ipcRenderer.invoke("launcher:get-storage-info"),
  clearLogs: () => ipcRenderer.invoke("launcher:clear-logs"),
  deleteLogFile: (filePath) => ipcRenderer.invoke("launcher:delete-log-file", filePath),
  exportLogFile: (filePath) => ipcRenderer.invoke("launcher:export-log-file", filePath),
  clearCache: () => ipcRenderer.invoke("launcher:clear-cache"),
  pickFolder: (options) => ipcRenderer.invoke("launcher:pick-folder", options || {}),
  pickFile: (options) => ipcRenderer.invoke("launcher:pick-file", options || {}),
  getPerformanceStats: () => ipcRenderer.invoke("launcher:get-performance-stats"),
  chooseSkin: (variant) => ipcRenderer.invoke("launcher:choose-skin", variant),
  equipSkin: (skinId) => ipcRenderer.invoke("launcher:equip-skin", skinId),
  removeSkin: (skinId) => ipcRenderer.invoke("launcher:remove-skin", skinId),
  updateSkinEntry: (patch) => ipcRenderer.invoke("launcher:update-skin-entry", patch),
  exportSkin: (skinId) => ipcRenderer.invoke("launcher:export-skin", skinId),
  equipCape: (capeId) => ipcRenderer.invoke("launcher:equip-cape", capeId),
  clearCape: () => ipcRenderer.invoke("launcher:clear-cape"),
  checkSocialAddress: (name) => ipcRenderer.invoke("launcher:check-social-address", name),
  setSocialAddress: (name) => ipcRenderer.invoke("launcher:set-social-address", name),
  openPath: (targetPath) => ipcRenderer.invoke("launcher:open-path", targetPath),
  windowAction: (action) => ipcRenderer.invoke("launcher:window", action),
  onStatus: (callback) => {
    const listener = (_event, status) => callback(status);
    ipcRenderer.on("launcher:status", listener);
    return () => ipcRenderer.removeListener("launcher:status", listener);
  },
  onLog: (callback) => {
    const listener = (_event, line) => callback(line);
    ipcRenderer.on("launcher:log", listener);
    return () => ipcRenderer.removeListener("launcher:log", listener);
  },
  onBoot: (callback) => {
    const listener = (_event, state) => callback(state);
    ipcRenderer.on("launcher:boot", listener);
    return () => ipcRenderer.removeListener("launcher:boot", listener);
  },
  onActivity: (callback) => {
    const listener = (_event, state) => callback(state);
    ipcRenderer.on("launcher:activity", listener);
    return () => ipcRenderer.removeListener("launcher:activity", listener);
  },
  onAuth: (callback) => {
    const listener = (_event, state) => callback(state);
    ipcRenderer.on("launcher:auth", listener);
    return () => ipcRenderer.removeListener("launcher:auth", listener);
  },
  exportInstance: (instanceId) => ipcRenderer.invoke("launcher:export-instance", instanceId),
  importInstance: () => ipcRenderer.invoke("launcher:import-instance"),
  installJava: (majorVersion) => ipcRenderer.invoke("launcher:install-java", majorVersion),
  getProfiles: () => ipcRenderer.invoke("launcher:get-profiles"),
  saveProfile: () => ipcRenderer.invoke("launcher:save-profile"),
  switchProfile: (profileId) => ipcRenderer.invoke("launcher:switch-profile", profileId),
  removeProfile: (profileId) => ipcRenderer.invoke("launcher:remove-profile", profileId),
  quickLaunch: () => ipcRenderer.invoke("launcher:quick-launch"),
  updateInstance: (patch) => ipcRenderer.invoke("launcher:update-instance", patch),
  getInstanceDetails: (request) => ipcRenderer.invoke("launcher:get-instance-details", request),
  previewVersionChange: (request) => ipcRenderer.invoke("launcher:preview-version-change", request),
  changeInstanceVersion: (request) => ipcRenderer.invoke("launcher:change-instance-version", request),
  openInstancePath: (request) => ipcRenderer.invoke("launcher:open-instance-path", request),
  deleteWorld: (request) => ipcRenderer.invoke("launcher:delete-world", request),
  analyzeCrash: () => ipcRenderer.invoke("launcher:analyze-crash"),
  getPartners: () => ipcRenderer.invoke("launcher:get-partners"),
  getFriendPresence: () => ipcRenderer.invoke("launcher:get-friend-presence"),
  getLanInfo: () => ipcRenderer.invoke("launcher:get-lan-info")
});
