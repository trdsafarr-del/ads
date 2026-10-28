const { loadState, saveState, setCors } = require('../_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = loadState();

  if (req.method === 'GET') {
    return res.status(200).json({
      devices: Object.values(state.devices || {}),
      queuedActions: state.queuedActions || {},
      playlistVersion: state.playlist.version || 1
    });
  }

  if (req.method === 'POST') {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    const { deviceId, status, currentVideoId, storageUsed, appVersion, lastSyncTime } = body;

    if (!deviceId) {
      return res.status(400).json({ acknowledged: false, error: 'Missing deviceId' });
    }

    state.devices[deviceId] = {
      ...(state.devices[deviceId] || {}),
      deviceId,
      status: status || 'PLAYING',
      currentVideoId: currentVideoId || null,
      storageUsed: Number(storageUsed) || 0,
      appVersion: appVersion || '1.0',
      lastSyncTime: Number(lastSyncTime) || Date.now(),
      lastSeen: Date.now(),
      paired: true
    };

    // Pop any queued remote action for this specific device or wildcard '*'
    let serverAction = null;
    if (state.queuedActions && state.queuedActions[deviceId]) {
      serverAction = state.queuedActions[deviceId];
      delete state.queuedActions[deviceId];
    } else if (state.queuedActions && state.queuedActions['*']) {
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
