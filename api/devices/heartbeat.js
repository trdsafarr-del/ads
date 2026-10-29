const { loadStateAsync, saveStateAsync, setCors } = require('../_store');

module.exports = async (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = await loadStateAsync();
  state.devices = state.devices || {};
  state.approvedCodes = state.approvedCodes || {};

  const cleanApprovedCodes = {};
  Object.entries(state.approvedCodes || {}).forEach(([k, v]) => {
    const cleanK = String(k).replace(/[^0-9]/g, '').trim();
    if (cleanK) cleanApprovedCodes[cleanK] = v;
  });

  if (req.method === 'GET') {
    const pairedDevices = Object.values(state.devices).filter((d) => {
      if (!d) return false;
      const dCode = d.pairingCode ? String(d.pairingCode).replace(/[^0-9]/g, '').trim() : '';
      return d.paired === true || (dCode && cleanApprovedCodes[dCode]);
    });
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
    const codeStr = pairingCode
      ? String(pairingCode).replace(/[^0-9]/g, '').trim()
      : (existing && existing.pairingCode ? String(existing.pairingCode).replace(/[^0-9]/g, '').trim() : '');
    const isGenuinelyPaired = Boolean((codeStr && cleanApprovedCodes[codeStr]) || (existing && existing.paired === true));

    let stateChanged = false;

    if (isGenuinelyPaired) {
      const resolvedName =
        (codeStr && cleanApprovedCodes[codeStr] && cleanApprovedCodes[codeStr].deviceName) ||
        (existing && existing.deviceName) ||
        `SignageTV (${deviceId.slice(-4)})`;

      state.devices[deviceId] = {
        ...(existing || {}),
        deviceId,
        pairingCode: codeStr,
        deviceName: resolvedName,
        status: status || 'PLAYING',
        currentVideoId: currentVideoId || null,
        storageUsed: Number(storageUsed) || 0,
        appVersion: appVersion || '1.0',
        screenOrientation:
          (existing && existing.screenOrientation) ||
          state.playlist.orientation ||
          screenOrientation ||
          'LANDSCAPE',
        videoOrientation:
          existing && existing.videoOrientation !== undefined && existing.videoOrientation !== null
            ? Number(existing.videoOrientation)
            : state.playlist.videoOrientation !== undefined && state.playlist.videoOrientation !== null
            ? Number(state.playlist.videoOrientation)
            : Number(videoOrientation) || 0,
        scaleMode:
          (existing && existing.scaleMode) ||
          state.playlist.scaleMode ||
          scaleMode ||
          'ZOOM',
        lastSyncTime: Number(lastSyncTime) || Date.now(),
        lastSeen: Date.now(),
        paired: true
      };
      stateChanged = true;
    }

    let serverAction = null;
    if (state.queuedActions && state.queuedActions[deviceId]) {
      serverAction = state.queuedActions[deviceId];
      delete state.queuedActions[deviceId];
      stateChanged = true;
    } else if (isGenuinelyPaired && state.queuedActions && state.queuedActions['*']) {
      serverAction = state.queuedActions['*'];
      delete state.queuedActions['*'];
      stateChanged = true;
    }

    if (stateChanged) {
      await saveStateAsync(state);
    }

    const dev = state.devices[deviceId] || {};
    const effectivePushTs = Math.max(
      Number(dev.lastPushTimestamp) || 0,
      Number(state.playlist.lastPushTimestamp) || 0
    );
    const effectivePushedId =
      (Number(dev.lastPushTimestamp) || 0) >= (Number(state.playlist.lastPushTimestamp) || 0) &&
      dev.lastPushedVideoId
        ? dev.lastPushedVideoId
        : state.playlist.lastPushedVideoId || null;

    return res.status(200).json({
      acknowledged: true,
      serverAction,
      playlistVersion: state.playlist.version || 1,
      lastPushedVideoId: effectivePushedId,
      lastPushTimestamp: effectivePushTs
    });
  }

  return res.status(405).json({ error: 'Method not allowed' });
};
