const { loadStateAsync, saveStateAsync, setCors } = require('../_store');

module.exports = async (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const state = await loadStateAsync();
  state.devices = state.devices || {};
  state.queuedActions = state.queuedActions || {};

  const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
  const targetDevice = body.deviceId || '*';
  const action = body.action;

  // If the client also sent its current videos array, sync it into state.playlist.videos first
  // so cross-lambda calls never fall back to stale default videos!
  if (Array.isArray(body.videos) && body.videos.length > 0) {
    state.playlist.videos = body.videos.map((v, idx) => ({
      id: v.id || `vid_${Date.now()}_${idx + 1}`,
      title: v.title || `Campaign Video #${idx + 1}`,
      url: v.url,
      order: idx + 1,
      duration: Number(v.duration) || 30,
      checksum: v.checksum || null,
      adTagUrl: v.enableVast === false ? null : (v.adTagUrl !== undefined ? v.adTagUrl : null)
    }));
  }

  const queueForTargetOrAll = (cmd) => {
    if (targetDevice === '*') {
      state.queuedActions['*'] = cmd;
      Object.keys(state.devices).forEach((id) => {
        state.queuedActions[id] = cmd;
      });
    } else {
      state.queuedActions[targetDevice] = cmd;
    }
  };

  const applyToTargetOrAll = (updater) => {
    if (targetDevice === '*') {
      Object.keys(state.devices).forEach((id) => {
        if (state.devices[id]) updater(state.devices[id]);
      });
    } else if (state.devices[targetDevice]) {
      updater(state.devices[targetDevice]);
    }
  };

  // Case A: Direct per-device or all-device orientation configuration
  if (action === 'setDeviceOrientation') {
    const { screenOrientation, videoOrientation, scaleMode } = body;
    if (targetDevice !== '*' && state.devices[targetDevice]) {
      const dev = state.devices[targetDevice];
      if (typeof screenOrientation === 'string' && screenOrientation.trim() !== '') {
        dev.screenOrientation = screenOrientation.trim();
        state.queuedActions[targetDevice] = `setScreenOrientation:${dev.screenOrientation}`;
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
        queueForTargetOrAll(`setScreenOrientation:${state.playlist.orientation}`);
      }
      if (videoOrientation !== undefined && videoOrientation !== null) {
        state.playlist.videoOrientation = Number(videoOrientation) || 0;
      }
      if (typeof scaleMode === 'string' && scaleMode.trim() !== '') {
        state.playlist.scaleMode = scaleMode.trim();
      }
      applyToTargetOrAll((d) => {
        if (typeof screenOrientation === 'string' && screenOrientation.trim() !== '') {
          d.screenOrientation = screenOrientation.trim();
        }
        if (videoOrientation !== undefined && videoOrientation !== null) {
          d.videoOrientation = Number(videoOrientation) || 0;
        }
        if (typeof scaleMode === 'string' && scaleMode.trim() !== '') {
          d.scaleMode = scaleMode.trim();
        }
      });
    }
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
    await saveStateAsync(state);

    return res.status(200).json({
      success: true,
      targetDevice,
      device: targetDevice !== '*' ? state.devices[targetDevice] : null,
      playlist: state.playlist,
      message: `Updated Screen & Video Orientation for ${
        targetDevice === '*' ? 'All TVs' : state.devices[targetDevice]?.deviceName || targetDevice
      }`
    });
  }

  // Case B: Multi-Video or Single-Video Instant Force-Play on Target Device(s)
  if (action === 'playVideosNow') {
    const allVideos = Array.isArray(state.playlist.videos) ? state.playlist.videos : [];
    const requestedIds = Array.isArray(body.videoIds)
      ? body.videoIds.filter((id) => allVideos.some((v) => v.id === id))
      : [];
    const effectiveIds =
      requestedIds.length > 0
        ? requestedIds
        : Array.isArray(body.videoIds) && body.videoIds.length > 0
        ? body.videoIds
        : allVideos.map((v) => v.id);
    const startVideoId =
      (body.startVideoId && effectiveIds.includes(body.startVideoId) ? body.startVideoId : effectiveIds[0]) ||
      (allVideos[0] && allVideos[0].id) ||
      '';
    const nowTs = Date.now();

    if (targetDevice === '*') {
      state.playlist.activeVideoIds =
        effectiveIds.length === allVideos.length ? null : effectiveIds;
      state.playlist.lastPushedVideoId = startVideoId;
      state.playlist.lastPushTimestamp = nowTs;
    }

    applyToTargetOrAll((d) => {
      d.activeVideoIds = effectiveIds.length === allVideos.length ? null : effectiveIds;
      d.lastPushedVideoId = startVideoId;
      d.lastPushTimestamp = nowTs;
    });

    queueForTargetOrAll(`playVideosNow:${startVideoId}:${nowTs}`);
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
    await saveStateAsync(state);

    const targetLabel =
      targetDevice === '*'
        ? 'All Paired TVs'
        : (state.devices[targetDevice] && state.devices[targetDevice].deviceName) || targetDevice;

    return res.status(200).json({
      success: true,
      targetDevice,
      videoIds: effectiveIds,
      startVideoId,
      playlist: state.playlist,
      message: `Playing ${effectiveIds.length} video(s) immediately on ${targetLabel}!`
    });
  }

  if (!action) {
    return res.status(400).json({ success: false, error: 'Missing action' });
  }

  queueForTargetOrAll(action);

  if (action === 'setPortrait') {
    if (targetDevice === '*') state.playlist.orientation = 'PORTRAIT';
    applyToTargetOrAll((d) => {
      d.screenOrientation = 'PORTRAIT';
    });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action === 'setLandscape') {
    if (targetDevice === '*') state.playlist.orientation = 'LANDSCAPE';
    applyToTargetOrAll((d) => {
      d.screenOrientation = 'LANDSCAPE';
    });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('setScreenOrientation:')) {
    const orient = action.substring('setScreenOrientation:'.length).trim();
    if (targetDevice === '*') state.playlist.orientation = orient;
    applyToTargetOrAll((d) => {
      d.screenOrientation = orient;
    });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('setVideoOrientation:')) {
    const deg = Number(action.substring('setVideoOrientation:'.length).trim()) || 0;
    if (targetDevice === '*') state.playlist.videoOrientation = deg;
    applyToTargetOrAll((d) => {
      d.videoOrientation = deg;
    });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('setScaleMode:')) {
    const sm = action.substring('setScaleMode:'.length).trim();
    if (targetDevice === '*') state.playlist.scaleMode = sm;
    applyToTargetOrAll((d) => {
      d.scaleMode = sm;
    });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('playVideo:')) {
    const vidId = action.substring('playVideo:'.length).trim();
    const nowTs = Date.now();
    if (targetDevice === '*') {
      state.playlist.lastPushedVideoId = vidId;
      state.playlist.lastPushTimestamp = nowTs;
    }
    applyToTargetOrAll((d) => {
      d.lastPushedVideoId = vidId;
      d.lastPushTimestamp = nowTs;
    });
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action.startsWith('downloadOffline:')) {
    const vidId = action.substring('downloadOffline:'.length).trim();
    state.playlist.offlineDownloadRequestedVideoId = vidId;
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action === 'muteAudio') {
    state.playlist.isMuted = true;
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  } else if (action === 'unmuteAudio') {
    state.playlist.isMuted = false;
    state.playlist.version = (Number(state.playlist.version) || 1) + 1;
  }

  await saveStateAsync(state);

  return res.status(200).json({
    success: true,
    targetDevice,
    action,
    playlist: state.playlist,
    message: `Remote command '${action}' sent to ${
      targetDevice === '*' ? 'all connected TVs' : targetDevice
    }`
  });
};
