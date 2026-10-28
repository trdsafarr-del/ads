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
  state.devices = state.devices || {};
  state.queuedActions = state.queuedActions || {};

  const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
  const targetDevice = body.deviceId || '*';
  const action = body.action;

  // Case A: Direct per-device orientation configuration { deviceId, action: 'setDeviceOrientation', screenOrientation, videoOrientation, scaleMode }
  if (action === 'setDeviceOrientation') {
    const { screenOrientation, videoOrientation, scaleMode } = body;
    if (targetDevice !== '*' && state.devices[targetDevice]) {
      const dev = state.devices[targetDevice];
      if (typeof screenOrientation === 'string' && screenOrientation.trim() !== '') {
        dev.screenOrientation = screenOrientation.trim();
      }
      if (videoOrientation !== undefined && videoOrientation !== null) {
        dev.videoOrientation = Number(videoOrientation) || 0;
      }
      if (typeof scaleMode === 'string' && scaleMode.trim() !== '') {
        dev.scaleMode = scaleMode.trim();
      }
      state.devices[targetDevice] = dev;
    } else if (targetDevice === '*') {
      if (typeof screenOrientation === 'string' && screenOrientation.trim() !== '') {
        state.playlist.orientation = screenOrientation.trim();
      }
      if (videoOrientation !== undefined && videoOrientation !== null) {
        state.playlist.videoOrientation = Number(videoOrientation) || 0;
      }
      if (typeof scaleMode === 'string' && scaleMode.trim() !== '') {
        state.playlist.scaleMode = scaleMode.trim();
      }
      Object.keys(state.devices).forEach((id) => {
        if (typeof screenOrientation === 'string' && screenOrientation.trim() !== '') {
          state.devices[id].screenOrientation = screenOrientation.trim();
        }
        if (videoOrientation !== undefined && videoOrientation !== null) {
          state.devices[id].videoOrientation = Number(videoOrientation) || 0;
        }
        if (typeof scaleMode === 'string' && scaleMode.trim() !== '') {
          state.devices[id].scaleMode = scaleMode.trim();
        }
      });
    }
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
    saveState(state);

    return res.status(200).json({
      success: true,
      targetDevice,
      device: targetDevice !== '*' ? state.devices[targetDevice] : null,
      playlist: state.playlist,
      message: `Updated Screen & Video Orientation for ${targetDevice === '*' ? 'All TVs' : (state.devices[targetDevice]?.deviceName || targetDevice)}`
    });
  }

  if (!action) {
    return res.status(400).json({ success: false, error: 'Missing action' });
  }

  state.queuedActions[targetDevice] = action;

  const applyToTargetOrAll = (updater) => {
    if (targetDevice === '*') {
      Object.keys(state.devices).forEach((id) => {
        if (state.devices[id]) updater(state.devices[id]);
      });
    } else if (state.devices[targetDevice]) {
      updater(state.devices[targetDevice]);
    }
  };

  if (action === 'setPortrait') {
    if (targetDevice === '*') state.playlist.orientation = 'PORTRAIT';
    applyToTargetOrAll((d) => { d.screenOrientation = 'PORTRAIT'; });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action === 'setLandscape') {
    if (targetDevice === '*') state.playlist.orientation = 'LANDSCAPE';
    applyToTargetOrAll((d) => { d.screenOrientation = 'LANDSCAPE'; });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('setScreenOrientation:')) {
    const orient = action.substring('setScreenOrientation:'.length).trim();
    if (targetDevice === '*') state.playlist.orientation = orient;
    applyToTargetOrAll((d) => { d.screenOrientation = orient; });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('setVideoOrientation:')) {
    const deg = Number(action.substring('setVideoOrientation:'.length).trim()) || 0;
    if (targetDevice === '*') state.playlist.videoOrientation = deg;
    applyToTargetOrAll((d) => { d.videoOrientation = deg; });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('setScaleMode:')) {
    const sm = action.substring('setScaleMode:'.length).trim();
    if (targetDevice === '*') state.playlist.scaleMode = sm;
    applyToTargetOrAll((d) => { d.scaleMode = sm; });
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
