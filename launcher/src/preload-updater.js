const { contextBridge, ipcRenderer } = require("electron");

/**
 * Bridge for the updater window.
 *
 * This window used to run with nodeIntegration and no context isolation, which gave the
 * page full require() access to the machine. It renders update progress and three
 * buttons, so it needs three channels and nothing else. They are listed here explicitly
 * rather than handing the page a general ipcRenderer, so the page cannot reach a channel
 * simply because one exists.
 */
contextBridge.exposeInMainWorld("riverUpdater", {
  retry: () => ipcRenderer.invoke("launcher:retry-updater"),
  window: (action) => ipcRenderer.invoke("launcher:window", action),

  // Returns its own unsubscribe rather than exposing removeListener, so the page never
  // holds a raw listener handle and cannot detach anything it did not attach.
  onActivity: (callback) => {
    const handler = (_event, payload) => callback(payload);
    ipcRenderer.on("launcher:activity", handler);
    return () => ipcRenderer.removeListener("launcher:activity", handler);
  }
});
