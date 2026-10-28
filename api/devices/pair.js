const { loadState, saveState, setCors } = require('../_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = loadState();
  state.approvedCodes = state.approvedCodes || {};
  state.devices = state.devices || {};

  if (req.method === 'GET') {
    // Only return devices that are genuinely paired with an approved 6-digit code
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

    // Case 1: Web Dashboard approving a 6-digit code entered by user
    if (body.action === 'approveCode' && body.pairingCode) {
      const code = String(body.pairingCode).trim();
      state.approvedCodes[code] = {
        pairingCode: code,
        deviceName: body.deviceName || `TV Screen (${code})`,
        approvedAt: Date.now()
      };
      saveState(state);
      return res.status(200).json({
        success: true,
        message: `Pairing code ${code} approved`,
        approvedCodes: state.approvedCodes
      });
    }

    // Case 2: Unpair device from Android Admin Panel or Web Dashboard
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
      // Queue unpair action in case unpair was triggered from Web Dashboard
      if (targetDeviceId) {
        state.queuedActions = state.queuedActions || {};
        state.queuedActions[targetDeviceId] = 'unpairDevice';
      }
      saveState(state);
      return res.status(200).json({
        success: true,
        message: 'Device unpaired'
      });
    }

    // Case 3: Android TV Device verifying pairing with { deviceId, pairingCode, deviceInfo }
    const { deviceId, pairingCode, deviceInfo } = body;
    if (!deviceId || !pairingCode) {
      return res.status(400).json({ success: false, error: 'Missing deviceId or pairingCode' });
    }

    const codeStr = String(pairingCode).trim();
    const approvedEntry = state.approvedCodes[codeStr];
    const isApproved = Boolean(approvedEntry);

    if (!isApproved) {
      // Do NOT add unpaired devices to state.devices (prevents any fake device entry)
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
    saveState(state);

    return res.status(200).json({
      success: true,
      deviceToken: `tok_${deviceId}_${Date.now()}`,
      organizationId: 'org_ads_broo_vercel',
      deviceName: resolvedName
    });
  }

  return res.status(405).json({ error: 'Method not allowed' });
};
