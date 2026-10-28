const { loadStateAsync, saveStateAsync, setCors, SAMPLE_VAST_TAG } = require('./_store');

module.exports = async (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = await loadStateAsync();
  state.devices = state.devices || {};
  state.queuedActions = state.queuedActions || {};

  if (req.method === 'GET') {
    const deviceId = req.query && req.query.deviceId;
    if (deviceId && state.devices[deviceId] && state.devices[deviceId].paired) {
      const dev = state.devices[deviceId];
      state.devices[deviceId] = {
        ...dev,
        lastPlaylistFetch: Date.now(),
        lastSeen: Date.now()
      };

      const allVideos = Array.isArray(state.playlist.videos) ? state.playlist.videos : [];
      const activeIds =
        Array.isArray(dev.activeVideoIds) && dev.activeVideoIds.length > 0
          ? dev.activeVideoIds
          : Array.isArray(state.playlist.activeVideoIds) && state.playlist.activeVideoIds.length > 0
          ? state.playlist.activeVideoIds
          : null;

      let deviceVideos = allVideos;
      if (activeIds) {
        const filtered = activeIds
          .map((id) => allVideos.find((v) => v.id === id))
          .filter(Boolean)
          .map((v, idx) => ({ ...v, order: idx + 1 }));
        if (filtered.length > 0) {
          deviceVideos = filtered;
        }
      }

      const devicePushTs = Math.max(
        Number(dev.lastPushTimestamp) || 0,
        Number(state.playlist.lastPushTimestamp) || 0
      );
      const devicePushedVideoId =
        (Number(dev.lastPushTimestamp) || 0) >= (Number(state.playlist.lastPushTimestamp) || 0) &&
        dev.lastPushedVideoId
          ? dev.lastPushedVideoId
          : state.playlist.lastPushedVideoId || (deviceVideos[0] && deviceVideos[0].id) || null;

      const devicePlaylist = {
        ...state.playlist,
        videos: deviceVideos,
        lastPushedVideoId: devicePushedVideoId,
        lastPushTimestamp: devicePushTs,
        orientation: dev.screenOrientation || state.playlist.orientation || 'LANDSCAPE',
        videoOrientation:
          dev.videoOrientation !== undefined && dev.videoOrientation !== null
            ? Number(dev.videoOrientation)
            : Number(state.playlist.videoOrientation) || 0,
        scaleMode: dev.scaleMode || state.playlist.scaleMode || 'ZOOM'
      };
      return res.status(200).json(devicePlaylist);
    }
    return res.status(200).json(state.playlist);
  }

  if (req.method === 'POST') {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    const current = state.playlist;

    if (body.deviceId && state.devices[body.deviceId]) {
      const dev = state.devices[body.deviceId];
      if (typeof body.orientation === 'string' && body.orientation.trim() !== '') {
        dev.screenOrientation = body.orientation.trim();
      }
      if (body.videoOrientation !== undefined && body.videoOrientation !== null) {
        dev.videoOrientation = Number(body.videoOrientation) || 0;
      }
      if (typeof body.scaleMode === 'string' && body.scaleMode.trim() !== '') {
        dev.scaleMode = body.scaleMode.trim();
      }
      dev.lastSeen = Date.now();
      state.devices[body.deviceId] = dev;
      current.version = (Number(current.version) || 1) + 1;
      state.playlist = current;
      await saveStateAsync(state);
      return res.status(200).json({
        success: true,
        device: dev,
        playlist: state.playlist
      });
    }

    if (body.action === 'addSingleVideo' && body.video) {
      const v = body.video;
      const newId = v.id || `vid_${Date.now()}`;
      const newVideo = {
        id: newId,
        title: v.title || `Campaign Video #${(current.videos || []).length + 1}`,
        url: v.url,
        order: (current.videos || []).length + 1,
        duration: Number(v.duration) || 30,
        checksum: v.checksum || null,
        adTagUrl: v.enableVast === false ? null : (v.adTagUrl || null)
      };
      current.videos = [...(current.videos || []), newVideo];
      current.lastPushedVideoId = newId;
      current.lastPushTimestamp = Date.now();
      current.activeVideoIds = null;
      Object.keys(state.devices).forEach((id) => {
        if (state.devices[id]) {
          state.devices[id].activeVideoIds = null;
          state.devices[id].lastPushedVideoId = newId;
          state.devices[id].lastPushTimestamp = current.lastPushTimestamp;
        }
      });
    } else if (Array.isArray(body.videos)) {
      current.videos = body.videos.map((v, idx) => ({
        id: v.id || `vid_${Date.now()}_${idx + 1}`,
        title: v.title || `Campaign Video #${idx + 1}`,
        url: v.url,
        order: idx + 1,
        duration: Number(v.duration) || 30,
        checksum: v.checksum || null,
        adTagUrl: v.enableVast === false ? null : (v.adTagUrl !== undefined ? v.adTagUrl : null)
      }));
    }

    if (Array.isArray(body.activeVideoIds)) {
      current.activeVideoIds = body.activeVideoIds.length > 0 ? body.activeVideoIds : null;
    } else if (body.activeVideoIds === null) {
      current.activeVideoIds = null;
    }

    if (typeof body.tickerText === 'string') {
      current.tickerText = body.tickerText;
    }

    if (typeof body.orientation === 'string' && body.orientation.trim() !== '') {
      const orient = body.orientation.trim();
      current.orientation = orient;
      Object.keys(state.devices).forEach((id) => {
        if (state.devices[id]) {
          state.devices[id].screenOrientation = orient;
        }
      });
    }

    if (body.videoOrientation !== undefined && body.videoOrientation !== null) {
      const vDeg = Number(body.videoOrientation) || 0;
      current.videoOrientation = vDeg;
      Object.keys(state.devices).forEach((id) => {
        if (state.devices[id]) {
          state.devices[id].videoOrientation = vDeg;
        }
      });
    }

    if (typeof body.scaleMode === 'string' && body.scaleMode.trim() !== '') {
      const sm = body.scaleMode.trim();
      current.scaleMode = sm;
      Object.keys(state.devices).forEach((id) => {
        if (state.devices[id]) {
          state.devices[id].scaleMode = sm;
        }
      });
    }

    if (typeof body.tickerEnabled === 'boolean') {
      current.tickerEnabled = body.tickerEnabled;
    }

    if (typeof body.isMuted === 'boolean') {
      current.isMuted = body.isMuted;
    }

    if (typeof body.kioskLocked === 'boolean') {
      current.kioskLocked = body.kioskLocked;
    }

    if (typeof body.autoBootEnabled === 'boolean') {
      current.autoBootEnabled = body.autoBootEnabled;
    }

    if (typeof body.lastPushedVideoId === 'string' && body.lastPushedVideoId.trim() !== '') {
      const pushedId = body.lastPushedVideoId.trim();
      const nowTs = Date.now();
      current.lastPushedVideoId = pushedId;
      current.lastPushTimestamp = nowTs;
      Object.keys(state.devices).forEach((id) => {
        if (state.devices[id]) {
          state.devices[id].lastPushedVideoId = pushedId;
          state.devices[id].lastPushTimestamp = nowTs;
        }
      });
    }

    if (typeof body.offlineDownloadRequestedVideoId === 'string') {
      current.offlineDownloadRequestedVideoId = body.offlineDownloadRequestedVideoId.trim() || null;
    }

    if (typeof body.vastTag === 'string') {
      current.ads = [
        {
          vastTag: body.vastTag || SAMPLE_VAST_TAG,
          frequency: Number(body.adFrequency) || 2
        }
      ];
    }

    current.version = (Number(current.version) || 1) + 1;
    current.schedule = [
      {
        startTime: '00:00',
        endTime: '23:59',
        videoIds: current.videos.map((v) => v.id)
      }
    ];

    state.playlist = current;
    await saveStateAsync(state);

    return res.status(200).json({
      success: true,
      playlist: state.playlist
    });
  }

  return res.status(405).json({ error: 'Method not allowed' });
};
