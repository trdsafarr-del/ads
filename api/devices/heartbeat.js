const { loadState, saveState, setCors } = require('../_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = loadState();
  state.devices = state.devices || {};
  state.approvedCodes = state.approvedCodes || {};

  if (req.method === 'GET') {
    const pairedDevices = Object.values(state.devices).filter(
      (d) => d && d.paired === true && d.pairingCode && state.approvedCodes[d.pairingCode]
    );
    return res.status(200).json({
      devices: pairedDevices,
      queuedActions: state.queuedActions || {},
      playlistVersion: state.playlist.version || 1
    });
  }

  if (req.method === 'POST') {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    const {
      deviceId,
      pairingCode,
      status,
      currentVideoId,
      storageUsed,
      appVersion,
      lastSyncTime,
      screenOrientation,
      videoOrientation,
      scaleMode
    } = body;

    if (!deviceId) {
      return res.status(400).json({ acknowledged: false, error: 'Missing deviceId' });
    }

    const existing = state.devices[deviceId];
    const codeStr = pairingCode ? String(pairingCode).trim() : (existing && existing.pairingCode);
    const isGenuinelyPaired = Boolean(codeStr && state.approvedCodes[codeStr]);

    if (isGenuinelyPaired) {
      state.devices[deviceId] = {
        ...(existing || {}),
        deviceId,
        pairingCode: codeStr,
        deviceName:
          (state.approvedCodes[codeStr] && state.approvedCodes[codeStr].deviceName) ||
          (existing && existing.deviceName) ||
          `SignageTV (${deviceId.slice(-4)})`,
        status: status || 'PLAYING',
        currentVideoId: currentVideoId || null,
        storageUsed: Number(storageUsed) || 0,
        appVersion: appVersion || '1.0',
        screenOrientation:
          (existing && existing.screenOrientation) ||
          screenOrientation ||
          state.playlist.orientation ||
          'LANDSCAPE',
        videoOrientation:
          existing && existing.videoOrientation !== undefined && existing.videoOrientation !== null
            ? Number(existing.videoOrientation)
            : videoOrientation !== undefined && videoOrientation !== null
            ? Number(videoOrientation)
            : Number(state.playlist.videoOrientation) || 0,
        scaleMode:
          (existing && existing.scaleMode) ||
          scaleMode ||
          state.playlist.scaleMode ||
          'ZOOM',
        lastSyncTime: Number(lastSyncTime) || Date.now(),
        lastSeen: Date.now(),
        paired: true
      };
    }

    // Pop any queued remote action for this specific device or wildcard '*'
    let serverAction = null;
    if (state.queuedActions && state.queuedActions[deviceId]) {
      serverAction = state.queuedActions[deviceId];
      delete state.queuedActions[deviceId];
    } else if (isGenuinelyPaired && state.queuedActions && state.queuedActions['*']) {
      serverAction = state.queuedActions['*'];
      delete state.queuedActions['*'];
    }

    saveState(state);

    return res.status(200).json({
      acknowledged: true,
      serverAction,
      playlistVersion: state.playlist.version || 1,
      lastPushedVideoId: state.playlist.lastPushedVideoId || null,
      lastPushTimestamp: state.playlist.lastPushTimestamp || 0
    });
  }

  return res.status(405).json({ error: 'Method not allowed' });
};
