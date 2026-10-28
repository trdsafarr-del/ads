const fs = require('fs');
const path = require('path');
const { setCors, UPLOAD_DIR, RELIABLE_URLS } = require('./_store');

module.exports = (req, res) => {
  setCors(res);
  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  try {
    if (!fs.existsSync(UPLOAD_DIR)) {
      fs.mkdirSync(UPLOAD_DIR, { recursive: true });
    }
  } catch (e) {}

  if (req.method === 'GET') {
    const videoId = (req.query && req.query.id) ? String(req.query.id).replace(/[^a-zA-Z0-9_-]/g, '_') : '';
    const filePath = path.join(UPLOAD_DIR, `${videoId}.mp4`);

    if (videoId && fs.existsSync(filePath)) {
      try {
        const stat = fs.statSync(filePath);
        const fileSize = stat.size;
        const range = req.headers.range;

        res.setHeader('Accept-Ranges', 'bytes');
        res.setHeader('Content-Type', 'video/mp4');

        if (range) {
          const parts = range.replace(/bytes=/, '').split('-');
          const start = parseInt(parts[0], 10);
          const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
          const chunkSize = end - start + 1;
          res.writeHead(206, {
            'Content-Range': `bytes ${start}-${end}/${fileSize}`,
            'Content-Length': chunkSize,
            'Content-Type': 'video/mp4'
          });
          fs.createReadStream(filePath, { start, end }).pipe(res);
          return;
        } else {
          res.writeHead(200, {
            'Content-Length': fileSize,
            'Content-Type': 'video/mp4'
          });
          fs.createReadStream(filePath).pipe(res);
          return;
        }
      } catch (e) {
        // Fallback redirect if read fails
      }
    }

    // Fallback to reliable stream if serverless cold-start cleared /tmp
    res.writeHead(302, { Location: RELIABLE_URLS[0] });
    return res.end();
  }

  if (req.method === 'POST') {
    try {
      const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
      const videoId = (body.id || `vid_upload_${Date.now()}`).replace(/[^a-zA-Z0-9_-]/g, '_');
      const base64Data = body.base64Data || '';

      if (!base64Data) {
        return res.status(400).json({ success: false, error: 'Missing video data' });
      }

      const cleanBase64 = base64Data.includes(',') ? base64Data.split(',')[1] : base64Data;
      const buffer = Buffer.from(cleanBase64, 'base64');
      const filePath = path.join(UPLOAD_DIR, `${videoId}.mp4`);
      fs.writeFileSync(filePath, buffer);

      const proto = req.headers['x-forwarded-proto'] || 'https';
      const host = req.headers['x-forwarded-host'] || req.headers.host || 'ads-broo.vercel.app';
      const fullUrl = `${proto}://${host}/api/upload?id=${videoId}`;

      return res.status(200).json({
        success: true,
        videoId,
        url: fullUrl,
        sizeBytes: buffer.length
      });
    } catch (e) {
      return res.status(500).json({
        success: false,
        error: e.message || 'Upload failed'
      });
    }
  }

  return res.status(405).json({ error: 'Method not allowed' });
};
