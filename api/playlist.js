const { loadState, saveState, setCors, SAMPLE_VAST_TAG } = require('./_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = loadState();

  if (req.method === 'GET') {
    const deviceId = req.query && req.query.deviceId;
    if (deviceId) {
      state.devices[deviceId] = {
        ...(state.devices[deviceId] || {}),
        deviceId,
        lastPlaylistFetch: Date.now(),
        status: (state.devices[deviceId] && state.devices[deviceId].status) || 'ONLINE'
      };
      saveState(state);
    }
    return res.status(200).json(state.playlist);
  }

  if (req.method === 'POST') {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    const current = state.playlist;

    if (Array.isArray(body.videos)) {
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

    if (typeof body.scaleMode === 'string') {
      current.scaleMode = body.scaleMode;
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
