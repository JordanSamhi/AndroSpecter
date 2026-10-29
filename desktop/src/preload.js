'use strict';
const { contextBridge, ipcRenderer } = require('electron');

// Only the trusted top-level UI receives this narrow bridge. No raw IPC or filesystem API is exposed.
if (window.top === window) {
  contextBridge.exposeInMainWorld('androspecter', Object.freeze({
    platform: process.platform,
    bootstrap: () => ipcRenderer.invoke('app:bootstrap'),
    chooseApk: () => ipcRenderer.invoke('apk:choose'),
    metadata: () => ipcRenderer.invoke('analysis:metadata'),
    inspect: options => ipcRenderer.invoke('analysis:inspect', options),
    graph: options => ipcRenderer.invoke('analysis:graph', options),
    importGraph: () => ipcRenderer.invoke('graph:import'),
    chooseSetting: kind => ipcRenderer.invoke('settings:choose', kind),
    saveSettings: settings => ipcRenderer.invoke('settings:save', settings),
    checkEnvironment: () => ipcRenderer.invoke('environment:check'),
    exportResult: (kind, format) => ipcRenderer.invoke('result:export', kind, format),
    copyCommand: kind => ipcRenderer.invoke('command:copy', kind),
    cancel: () => ipcRenderer.invoke('analysis:cancel'),
    onUpdate: callback => {
      if (typeof callback !== 'function') throw new TypeError('An update callback is required.');
      const listener = (_event, update) => callback(update);
      ipcRenderer.on('analysis:update', listener);
      return () => ipcRenderer.removeListener('analysis:update', listener);
    }
  }));
}
