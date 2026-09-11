const { contextBridge, ipcRenderer } = require("electron");

/**
 * Bridge for the game log window. Same reasoning as the updater bridge: this window
 * showed the game's stdout with full Node access, which is a lot of authority for a
 * text view. Only the channels it actually uses are exposed.
 */
contextBridge.exposeInMainWorld("riverLogs", {
  ready: () => ipcRenderer.invoke("logs:ready"),
  copy: (text) => ipcRenderer.invoke("logs:copy", text),
  openFolder: () => ipcRenderer.invoke("logs:open-folder"),
  window: (action) => ipcRenderer.invoke("logs:window", action),

  onLog: (callback) => {
    const handler = (_event, payload) => callback(payload);
    ipcRenderer.on("launcher:log", handler);
    return () => ipcRenderer.removeListener("launcher:log", handler);
  },
  onCleared: (callback) => {
    const handler = () => callback();
    ipcRenderer.on("logs:cleared", handler);
    return () => ipcRenderer.removeListener("logs:cleared", handler);
  },
  onMaximized: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on("logs:maximized", handler);
    return () => ipcRenderer.removeListener("logs:maximized", handler);
  }
});
