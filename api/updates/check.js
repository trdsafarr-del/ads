const { setCors } = require('../_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  return res.status(200).json({
    updateAvailable: false,
    versionCode: 1,
    versionName: '1.0',
    apkUrl: null
  });
};
