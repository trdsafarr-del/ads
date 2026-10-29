const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = process.env.APP_PORT || 3000;
const PUBLIC_DIR = path.join(__dirname, 'public');

function getApiHandler(pathname) {
  const handlerMap = {
    '/api/playlist': './api/playlist',
    '/api/devices/action': './api/devices/action',
    '/api/devices/heartbeat': './api/devices/heartbeat',
    '/api/devices/pair': './api/devices/pair',
    '/api/upload': './api/upload',
    '/api/updates/check': './api/updates/check'
  };
  const modPath = handlerMap[pathname];
  if (!modPath) return null;
  try {
    const resolvedMod = require.resolve(modPath);
    delete require.cache[resolvedMod];
    const resolvedStore = require.resolve('./api/_store');
    delete require.cache[resolvedStore];
    return require(modPath);
  } catch (e) {
    return require(modPath);
  }
}

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.mp4': 'video/mp4',
  '.ico': 'image/x-icon'
};

function ensureResponseHelpers(res) {
  if (!res.status) {
    res.status = function(code) {
      res.statusCode = code;
      return res;
    };
  }
  if (!res.json) {
    res.json = function(data) {
      if (!res.headersSent) {
        res.setHeader('Content-Type', 'application/json');
      }
      res.end(JSON.stringify(data));
      return res;
    };
  }
}

const server = http.createServer(async (req, res) => {
  const parsedUrl = url.parse(req.url, true);
  const rawPathname = parsedUrl.pathname || '/';
  const pathname = rawPathname.length > 1 ? rawPathname.replace(/\/$/, '') : rawPathname;

  // Attach query object for Vercel handler compatibility
  req.query = parsedUrl.query || {};

  // Check if it's an API route
  const apiHandler = getApiHandler(pathname);
  if (apiHandler) {
    ensureResponseHelpers(res);

    if (req.method === 'GET' || req.method === 'HEAD' || req.method === 'OPTIONS') {
      req.body = {};
      try {
        await apiHandler(req, res);
      } catch (err) {
        console.error(`API Error on ${pathname}:`, err);
        if (!res.headersSent) {
          res.statusCode = 500;
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify({ error: err.message || 'Internal Server Error' }));
        }
      }
      return;
    }

    // Read request body for POST/PUT/PATCH
    const chunks = [];
    let totalBytes = 0;
    req.on('data', chunk => {
      chunks.push(chunk);
      totalBytes += chunk.length;
      // Safeguard max 50 MB
      if (totalBytes > 50 * 1024 * 1024) {
        req.destroy();
      }
    });

    req.on('end', async () => {
      const bodyData = Buffer.concat(chunks).toString('utf8');
      if (bodyData) {
        try {
          req.body = JSON.parse(bodyData);
        } catch (e) {
          req.body = bodyData;
        }
      } else {
        req.body = {};
      }

      try {
        await apiHandler(req, res);
      } catch (err) {
        console.error(`API Error on ${pathname}:`, err);
        if (!res.headersSent) {
          res.statusCode = 500;
          res.setHeader('Content-Type', 'application/json');
          res.end(JSON.stringify({ error: err.message || 'Internal Server Error' }));
        }
      }
    });
    return;
  }

  // Otherwise serve static files from public/
  let filePath = path.join(PUBLIC_DIR, pathname === '/' ? 'index.html' : pathname);

  // Prevent path traversal
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.statusCode = 403;
    res.end('Forbidden');
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      // Fallback to index.html for SPA routing
      filePath = path.join(PUBLIC_DIR, 'index.html');
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    // Handle Range requests for video/audio streaming
    const stat = fs.statSync(filePath);
    const totalSize = stat.size;
    const range = req.headers.range;

    if (range && contentType.startsWith('video/')) {
      const parts = range.replace(/bytes=/, '').split('-');
      const start = parseInt(parts[0], 10) || 0;
      const end = parts[1] ? parseInt(parts[1], 10) : totalSize - 1;
      const chunkSize = (end - start) + 1;

      res.writeHead(206, {
        'Content-Range': `bytes ${start}-${end}/${totalSize}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': chunkSize,
        'Content-Type': contentType
      });
      fs.createReadStream(filePath, { start, end }).pipe(res);
    } else {
      res.writeHead(200, {
        'Content-Length': totalSize,
        'Content-Type': contentType,
        'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=3600'
      });
      fs.createReadStream(filePath).pipe(res);
    }
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`SignageTV Local Dev Server running on http://0.0.0.0:${PORT}`);
});
