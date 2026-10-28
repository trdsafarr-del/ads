const { loadStateAsync, saveStateAsync, setCors } = require('../_store');

module.exports = async (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = await loadStateAsync();
  state.approvedCodes = state.approvedCodes || {};
  state.devices = state.devices || {};

  if (req.method === 'GET') {
    const pairedDevices = Object.values(state.devices).filter(
      (d) => d && d.paired === true && d.pairingCode && state.approvedCodes[d.pairingCode]
    );
    return res.status(200).json({
      autoApprovePairing: false,
      approvedCodes: state.approvedCodes,
      devices: pairedDevices
    });
  }

  if (req.method === 'POST') {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});

    if (body.action === 'approveCode' && body.pairingCode) {
      const code = String(body.pairingCode).trim();
      state.approvedCodes[code] = {
        pairingCode: code,
        deviceName: body.deviceName || `TV Screen (${code})`,
        approvedAt: Date.now()
      };
      await saveStateAsync(state);
      return res.status(200).json({
        success: true,
        message: `Pairing code ${code} approved`,
        approvedCodes: state.approvedCodes
      });
    }

    if (body.action === 'unpairDevice') {
      const targetDeviceId = body.deviceId;
      const targetCode = body.pairingCode ? String(body.pairingCode).trim() : null;

      if (targetDeviceId && state.devices[targetDeviceId]) {
        const existingCode = state.devices[targetDeviceId].pairingCode;
        if (existingCode && state.approvedCodes[existingCode]) {
          delete state.approvedCodes[existingCode];
        }
        delete state.devices[targetDeviceId];
      }
      if (targetCode && state.approvedCodes[targetCode]) {
        delete state.approvedCodes[targetCode];
      }
      if (targetDeviceId) {
        state.queuedActions = state.queuedActions || {};
        state.queuedActions[targetDeviceId] = 'unpairDevice';
      }
      await saveStateAsync(state);
      return res.status(200).json({
        success: true,
        message: 'Device unpaired'
      });
    }

    const { deviceId, pairingCode, deviceInfo } = body;
    if (!deviceId || !pairingCode) {
      return res.status(400).json({ success: false, error: 'Missing deviceId or pairingCode' });
    }

    const codeStr = String(pairingCode).trim();
    const approvedEntry = state.approvedCodes[codeStr];
    const isApproved = Boolean(approvedEntry);

    if (!isApproved) {
      return res.status(200).json({
        success: false,
        deviceToken: null,
        organizationId: null
      });
    }

    const resolvedName =
      approvedEntry.deviceName ||
      (state.devices[deviceId] && state.devices[deviceId].deviceName) ||
      `SignageTV (${deviceId.slice(-4)})`;

    const existingDev = state.devices[deviceId] || {};
    state.devices[deviceId] = {
      ...existingDev,
      deviceId,
      pairingCode: codeStr,
      deviceInfo: deviceInfo || 'Android TV Signage Player',
      deviceName: resolvedName,
      screenOrientation: existingDev.screenOrientation || state.playlist.orientation || 'LANDSCAPE',
      videoOrientation:
        existingDev.videoOrientation !== undefined && existingDev.videoOrientation !== null
          ? Number(existingDev.videoOrientation)
          : Number(state.playlist.videoOrientation) || 0,
      scaleMode: existingDev.scaleMode || state.playlist.scaleMode || 'ZOOM',
      paired: true,
      status: 'PLAYING',
      lastSeen: Date.now()
    };
    await saveStateAsync(state);

    return res.status(200).json({
      success: true,
      deviceToken: `tok_${deviceId}_${Date.now()}`,
      organizationId: 'org_ads_broo_vercel',
      deviceName: resolvedName
    });
  }

  return res.status(405).json({ error: 'Method not allowed' });
};
