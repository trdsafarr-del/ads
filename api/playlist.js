const { loadState, saveState, setCors, SAMPLE_VAST_TAG } = require('./_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = loadState();
  state.devices = state.devices || {};

  if (req.method === 'GET') {
    const deviceId = req.query && req.query.deviceId;
    if (deviceId && state.devices[deviceId] && state.devices[deviceId].paired) {
      const dev = state.devices[deviceId];
      state.devices[deviceId] = {
        ...dev,
        lastPlaylistFetch: Date.now(),
        lastSeen: Date.now()
      };
      saveState(state);

      // Return playlist tailored with this specific device's Screen Orientation, Video Orientation & Scale Mode
      const devicePlaylist = {
        ...state.playlist,
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

    // Support per-device orientation updates from Android TV or Web Dashboard
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
      saveState(state);
      return res.status(200).json({
        success: true,
        device: dev,
        playlist: state.playlist
      });
    }

    // Support adding/pushing a single video directly from Android or Web
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

    if (typeof body.tickerText === 'string') {
      current.tickerText = body.tickerText;
    }

    if (typeof body.orientation === 'string') {
      current.orientation = body.orientation;
    }

    if (body.videoOrientation !== undefined && body.videoOrientation !== null) {
      current.videoOrientation = Number(body.videoOrientation) || 0;
    }

    if (typeof body.scaleMode === 'string') {
      current.scaleMode = body.scaleMode;
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
      current.lastPushedVideoId = body.lastPushedVideoId.trim();
      current.lastPushTimestamp = Date.now();
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
    saveState(state);

    return res.status(200).json({
      success: true,
      playlist: state.playlist
    });
  }

  return res.status(405).json({ error: 'Method not allowed' });
};
