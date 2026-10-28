const fs = require('fs');
const path = require('path');
const { loadState, saveState, setCors, UPLOAD_DIR } = require('./_store');

async function mirrorBufferToPublicCdn(buffer, filename) {
  const safeName = (filename || `video_${Date.now()}.mp4`).replace(/[^a-zA-Z0-9._-]/g, '_');

  // Provider 1: tmpfiles.org direct stream CDN (supports up to 100 MB, fast Range requests)
  try {
    if (typeof FormData !== 'undefined' && typeof Blob !== 'undefined') {
      const fd = new FormData();
      fd.append('file', new Blob([buffer], { type: 'video/mp4' }), safeName);
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 12000);
      const res = await fetch('https://tmpfiles.org/api/v1/upload', {
        method: 'POST',
        body: fd,
        signal: controller.signal
      });
      clearTimeout(timer);
      if (res.ok) {
        const json = await res.json();
        const pageUrl = json && json.data && json.data.url;
        if (pageUrl) {
          return String(pageUrl)
            .replace(/^http:\/\//i, 'https://')
            .replace('https://tmpfiles.org/', 'https://tmpfiles.org/dl/');
        }
      }
    }
  } catch (e) {}

  // Provider 2: Litterbox / Catbox direct MP4 host
  try {
    if (typeof FormData !== 'undefined' && typeof Blob !== 'undefined') {
      const fd = new FormData();
      fd.append('reqtype', 'fileupload');
      fd.append('time', '72h');
      fd.append('fileToUpload', new Blob([buffer], { type: 'video/mp4' }), safeName);
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 12000);
      const res = await fetch('https://litterbox.catbox.moe/resources/internals/api.php', {
        method: 'POST',
        body: fd,
        signal: controller.signal
      });
      clearTimeout(timer);
      if (res.ok) {
        const text = (await res.text()).trim();
        if (text.startsWith('https://')) {
          return text;
        }
      }
    }
  } catch (e) {}

  return null;
}

function streamBufferWithRange(req, res, buffer) {
  const fileSize = buffer.length;
  const range = req.headers.range;

  res.setHeader('Accept-Ranges', 'bytes');
  res.setHeader('Content-Type', 'video/mp4');
  res.setHeader('Cache-Control', 'public, max-age=86400');

  if (range) {
    const parts = range.replace(/bytes=/, '').split('-');
    const start = parseInt(parts[0], 10) || 0;
    const end = parts[1] ? Math.min(parseInt(parts[1], 10), fileSize - 1) : fileSize - 1;
    const chunkSize = Math.max(0, end - start + 1);
    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Content-Length': chunkSize,
      'Content-Type': 'video/mp4'
    });
    return res.end(buffer.subarray(start, end + 1));
  } else {
    res.writeHead(200, {
      'Content-Length': fileSize,
      'Content-Type': 'video/mp4'
    });
    return res.end(buffer);
  }
}

module.exports = async (req, res) => {
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
    const videoId = req.query && req.query.id ? String(req.query.id).replace(/[^a-zA-Z0-9_-]/g, '_') : '';
    if (!videoId) {
      return res.status(400).json({ error: 'Missing video id' });
    }

    const state = loadState();
    const meta = state.uploadedVideos && state.uploadedVideos[videoId];

    // 1. Stream directly from local /tmp file if present on this instance
    const filePath = path.join(UPLOAD_DIR, `${videoId}.mp4`);
    if (fs.existsSync(filePath)) {
      try {
        const stat = fs.statSync(filePath);
        if (stat.size > 0) {
          const buffer = fs.readFileSync(filePath);
          return streamBufferWithRange(req, res, buffer);
        }
      } catch (e) {}
    }

    // 2. If stored in state.uploadedVideos as base64, decode & stream
    if (meta && meta.base64) {
      try {
        const buffer = Buffer.from(meta.base64, 'base64');
        if (buffer.length > 0) {
          return streamBufferWithRange(req, res, buffer);
        }
      } catch (e) {}
    }

    // 3. If mirrored to a persistent CDN URL, redirect to the real uploaded video
    if (meta && meta.cdnUrl) {
      res.writeHead(302, { Location: meta.cdnUrl });
      return res.end();
    }

    return res.status(404).json({ error: 'Uploaded video not found on this instance' });
  }

  if (req.method === 'POST') {
    try {
      const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
      const videoId = (body.id || `vid_upload_${Date.now()}`).replace(/[^a-zA-Z0-9_-]/g, '_');
      const filename = body.filename || `${videoId}.mp4`;
      const filePath = path.join(UPLOAD_DIR, `${videoId}.mp4`);

      // Support Chunked Upload so files > 4 MB never fail on Vercel's request size limit
      if (body.action === 'uploadChunk') {
        const chunkIndex = Number(body.chunkIndex) || 0;
        const totalChunks = Number(body.totalChunks) || 1;
        const rawChunk = body.chunkBase64 || '';
        const cleanChunk = rawChunk.includes(',') ? rawChunk.split(',')[1] : rawChunk;
        const chunkBuf = Buffer.from(cleanChunk, 'base64');

        if (chunkIndex === 0) {
          fs.writeFileSync(filePath, chunkBuf);
        } else {
          fs.appendFileSync(filePath, chunkBuf);
        }

        if (chunkIndex < totalChunks - 1) {
          return res.status(200).json({
            success: true,
            partial: true,
            chunkIndex,
            totalChunks
          });
        }
      } else {
        // Single-shot upload
        const base64Data = body.base64Data || '';
        if (!base64Data) {
          return res.status(400).json({ success: false, error: 'Missing video data' });
        }
        const cleanBase64 = base64Data.includes(',') ? base64Data.split(',')[1] : base64Data;
        const buffer = Buffer.from(cleanBase64, 'base64');
        fs.writeFileSync(filePath, buffer);
      }

      const finalBuffer = fs.readFileSync(filePath);
      const proto = req.headers['x-forwarded-proto'] || 'https';
      const host = req.headers['x-forwarded-host'] || req.headers.host || 'ads-broo.vercel.app';
      const localStreamUrl = `${proto}://${host}/api/upload?id=${videoId}`;

      // Mirror to public direct-stream CDN so across any serverless lambda cold-start the exact uploaded video plays
      const cdnUrl = await mirrorBufferToPublicCdn(finalBuffer, filename);
      const bestUrl = cdnUrl || localStreamUrl;

      const state = loadState();
      state.uploadedVideos = state.uploadedVideos || {};
      state.uploadedVideos[videoId] = {
        videoId,
        filename,
        sizeBytes: finalBuffer.length,
        cdnUrl: cdnUrl || null,
        url: bestUrl,
        base64: finalBuffer.length <= 2.2 * 1024 * 1024 ? finalBuffer.toString('base64') : null,
        uploadedAt: Date.now()
      };
      saveState(state);

      return res.status(200).json({
        success: true,
        videoId,
        url: bestUrl,
        localStreamUrl,
        cdnUrl,
        sizeBytes: finalBuffer.length
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
