const { loadState, saveState, setCors } = require('../_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  const state = loadState();

  if (req.method === 'GET') {
    return res.status(200).json({
      autoApprovePairing: state.autoApprovePairing !== false,
      approvedCodes: state.approvedCodes || {},
      devices: Object.values(state.devices || {})
    });
  }

  if (req.method === 'POST') {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});

    // Case 1: Web Dashboard approving a 6-digit code or toggling auto-approve
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

    if (body.action === 'setAutoApprove') {
      state.autoApprovePairing = Boolean(body.enabled);
      saveState(state);
      return res.status(200).json({
        success: true,
        autoApprovePairing: state.autoApprovePairing
      });
    }

    // Case 2: Android TV Device polling /api/devices/pair with { deviceId, pairingCode, deviceInfo }
    const { deviceId, pairingCode, deviceInfo } = body;
    if (!deviceId || !pairingCode) {
      return res.status(400).json({ success: false, error: 'Missing deviceId or pairingCode' });
    }

    const codeStr = String(pairingCode).trim();
    const isApproved = Boolean(state.autoApprovePairing) || Boolean(state.approvedCodes[codeStr]);

    state.devices[deviceId] = {
      ...(state.devices[deviceId] || {}),
      deviceId,
      pairingCode: codeStr,
      deviceInfo: deviceInfo || 'Android TV Signage Player',
      deviceName:
        (state.approvedCodes[codeStr] && state.approvedCodes[codeStr].deviceName) ||
        (state.devices[deviceId] && state.devices[deviceId].deviceName) ||
        `SignageTV (${deviceId.slice(-4)})`,
      paired: isApproved,
      status: isApproved ? 'PLAYING' : 'AWAITING_PAIR',
      lastSeen: Date.now()
    };
    saveState(state);

    if (isApproved) {
      return res.status(200).json({
        success: true,
        deviceToken: `tok_${deviceId}_${Date.now()}`,
        organizationId: 'org_ads_broo_vercel'
      });
    }

    return res.status(200).json({
      success: false,
      deviceToken: null,
      organizationId: null
    });
  }

  return res.status(405).json({ error: 'Method not allowed' });
};
