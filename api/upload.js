const fs = require('fs');
const path = require('path');
const { loadStateAsync, saveStateAsync, setCors, UPLOAD_DIR, FIRESTORE_BASE_URL } = require('./_store');

async function saveChunkToFirestore(videoId, chunkIndex, totalChunks, base64Data) {
  const docId = `${videoId}_chunk_${chunkIndex}`;
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    await fetch(`${FIRESTORE_BASE_URL}/cms_uploads/${docId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fields: {
          videoId: { stringValue: String(videoId) },
          chunkIndex: { integerValue: String(chunkIndex) },
          totalChunks: { integerValue: String(totalChunks) },
          data: { stringValue: String(base64Data) },
          mimeType: { stringValue: 'video/mp4' },
          updatedAtMs: { integerValue: String(Date.now()) }
        }
      }),
      signal: controller.signal
    });
    clearTimeout(timer);
  } catch (e) {}
}

async function reassembleFromFirestoreChunks(videoId, expectedTotalChunks) {
  try {
    // First fetch chunk 0 to discover totalChunks if not provided
    const firstRes = await fetch(`${FIRESTORE_BASE_URL}/cms_uploads/${videoId}_chunk_0`);
    if (!firstRes.ok) return null;
    const firstDoc = await firstRes.json();
    const fields0 = (firstDoc && firstDoc.fields) || {};
    const b64_0 = fields0.data && fields0.data.stringValue;
    if (!b64_0) return null;
    const total = Number((fields0.totalChunks && fields0.totalChunks.integerValue) || expectedTotalChunks || 1);

    const buffers = [Buffer.from(b64_0, 'base64')];
    for (let i = 1; i < total; i++) {
      const r = await fetch(`${FIRESTORE_BASE_URL}/cms_uploads/${videoId}_chunk_${i}`);
      if (!r.ok) return null;
      const d = await r.json();
      const b64 = d && d.fields && d.fields.data && d.fields.data.stringValue;
      if (!b64) return null;
      buffers.push(Buffer.from(b64, 'base64'));
    }
    return Buffer.concat(buffers);
  } catch (e) {
    return null;
  }
}

async function mirrorBufferToPublicCdn(buffer, filename) {
  const safeName = (filename || `video_${Date.now()}.mp4`).replace(/[^a-zA-Z0-9._-]/g, '_');

  // Litterbox / Catbox direct MP4 host (serves real video/mp4 with HTTP 206 byte-range & CORS)
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

    const filePath = path.join(UPLOAD_DIR, `${videoId}.mp4`);
    // 1. Stream directly from local /tmp file if present on this instance
    if (fs.existsSync(filePath)) {
      try {
        const stat = fs.statSync(filePath);
        if (stat.size > 0) {
          const buffer = fs.readFileSync(filePath);
          return streamBufferWithRange(req, res, buffer);
        }
      } catch (e) {}
    }

    // 2. Reassemble from Cloud Firestore chunks (/cms_uploads/{videoId}_chunk_*) across cold-starts!
    const state = await loadStateAsync();
    const meta = state.uploadedVideos && state.uploadedVideos[videoId];
    const firestoreBuffer = await reassembleFromFirestoreChunks(videoId, meta && meta.totalChunks);
    if (firestoreBuffer && firestoreBuffer.length > 0) {
      try {
        fs.writeFileSync(filePath, firestoreBuffer);
      } catch (e) {}
      return streamBufferWithRange(req, res, firestoreBuffer);
    }

    // 3. If mirrored to a persistent direct-MP4 CDN URL, redirect
    if (meta && meta.cdnUrl && !String(meta.cdnUrl).includes('tmpfiles.org')) {
      res.writeHead(302, { Location: meta.cdnUrl });
      return res.end();
    }

    return res.status(404).json({ error: 'Uploaded video not found' });
  }

  if (req.method === 'POST') {
    try {
      const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
      const videoId = (body.id || `vid_upload_${Date.now()}`).replace(/[^a-zA-Z0-9_-]/g, '_');
      const filename = body.filename || `${videoId}.mp4`;
      const filePath = path.join(UPLOAD_DIR, `${videoId}.mp4`);

      let totalChunksSaved = 1;

      if (body.action === 'uploadChunk') {
        const chunkIndex = Number(body.chunkIndex) || 0;
        const totalChunks = Number(body.totalChunks) || 1;
        totalChunksSaved = totalChunks;
        const rawChunk = body.chunkBase64 || '';
        const cleanChunk = rawChunk.includes(',') ? rawChunk.split(',')[1] : rawChunk;
        const chunkBuf = Buffer.from(cleanChunk, 'base64');

        if (chunkIndex === 0) {
          fs.writeFileSync(filePath, chunkBuf);
        } else {
          fs.appendFileSync(filePath, chunkBuf);
        }

        // Persist chunk in Cloud Firestore so any serverless instance or Android TV can reassemble it!
        if (cleanChunk.length <= 900000) {
          await saveChunkToFirestore(videoId, chunkIndex, totalChunks, cleanChunk);
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
        const base64Data = body.base64Data || '';
        if (!base64Data) {
          return res.status(400).json({ success: false, error: 'Missing video data' });
        }
        const cleanBase64 = base64Data.includes(',') ? base64Data.split(',')[1] : base64Data;
        const buffer = Buffer.from(cleanBase64, 'base64');
        fs.writeFileSync(filePath, buffer);

        // Save in 650 KB chunks to Cloud Firestore
        const CHUNK_LEN = 650 * 1024;
        const numChunks = Math.max(1, Math.ceil(cleanBase64.length / CHUNK_LEN));
        totalChunksSaved = numChunks;
        for (let i = 0; i < numChunks; i++) {
          const sliceB64 = cleanBase64.slice(i * CHUNK_LEN, (i + 1) * CHUNK_LEN);
          await saveChunkToFirestore(videoId, i, numChunks, sliceB64);
        }
      }

      let finalBuffer = Buffer.alloc(0);
      if (fs.existsSync(filePath)) {
        finalBuffer = fs.readFileSync(filePath);
      }
      if (finalBuffer.length === 0) {
        const recovered = await reassembleFromFirestoreChunks(videoId, totalChunksSaved);
        if (recovered) finalBuffer = recovered;
      }

      const proto = req.headers['x-forwarded-proto'] || 'https';
      const host = req.headers['x-forwarded-host'] || req.headers.host || 'ads-broo.vercel.app';
      const localStreamUrl = `${proto}://${host}/api/upload?id=${videoId}`;

      const cdnUrl = finalBuffer.length > 0 ? await mirrorBufferToPublicCdn(finalBuffer, filename) : null;
      const bestUrl = cdnUrl || localStreamUrl;

      const state = await loadStateAsync();
      state.uploadedVideos = state.uploadedVideos || {};
      state.uploadedVideos[videoId] = {
        videoId,
        filename,
        sizeBytes: finalBuffer.length,
        totalChunks: totalChunksSaved,
        cdnUrl: cdnUrl || null,
        url: bestUrl,
        uploadedAt: Date.now()
      };
      await saveStateAsync(state);

      return res.status(200).json({
        success: true,
        videoId,
        url: bestUrl,
        localStreamUrl,
        cdnUrl,
        totalChunks: totalChunksSaved,
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
