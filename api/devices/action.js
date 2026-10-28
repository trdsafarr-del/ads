const { loadState, saveState, setCors } = require('../_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const state = loadState();
  const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
  const targetDevice = body.deviceId || '*';
  const action = body.action;

  if (!action) {
    return res.status(400).json({ success: false, error: 'Missing action' });
  }

  state.queuedActions = state.queuedActions || {};
  state.queuedActions[targetDevice] = action;

  if (action === 'setPortrait') {
    state.playlist.orientation = 'PORTRAIT';
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action === 'setLandscape') {
    state.playlist.orientation = 'LANDSCAPE';
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('playVideo:')) {
    const vidId = action.substring('playVideo:'.length);
    state.playlist.lastPushedVideoId = vidId;
    state.playlist.lastPushTimestamp = Date.now();
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('downloadOffline:')) {
    const vidId = action.substring('downloadOffline:'.length);
    state.playlist.offlineDownloadRequestedVideoId = vidId;
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action === 'muteAudio') {
    state.playlist.isMuted = true;
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action === 'unmuteAudio') {
    state.playlist.isMuted = false;
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  }

  saveState(state);

  return res.status(200).json({
    success: true,
    targetDevice,
    action,
    playlist: state.playlist,
    message: `Remote command '${action}' sent to ${targetDevice === '*' ? 'all connected TVs' : targetDevice}`
  });
};
