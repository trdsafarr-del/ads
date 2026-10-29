const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = process.env.APP_PORT || 3000;
const PUBLIC_DIR = path.join(__dirname, 'public');

const API_HANDLERS = {
  '/api/playlist': require('./api/playlist'),
  '/api/devices/action': require('./api/devices/action'),
  '/api/devices/heartbeat': require('./api/devices/heartbeat'),
  '/api/devices/pair': require('./api/devices/pair'),
  '/api/upload': require('./api/upload')
};

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

const server = http.createServer(async (req, res) => {
  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname || '/';

  // Attach query object for Vercel handler compatibility
  req.query = parsedUrl.query || {};

  // Check if it's an API route
  const apiHandler = API_HANDLERS[pathname];
  if (apiHandler) {
    // Read request body for POST/PUT/PATCH
    let bodyData = '';
    req.on('data', chunk => {
      bodyData += chunk;
      // Safeguard max 50 MB
      if (bodyData.length > 50 * 1024 * 1024) {
        req.destroy();
      }
    });

    req.on('end', async () => {
      if (bodyData) {
        try {
          req.body = JSON.parse(bodyData);
        } catch (e) {
          req.body = bodyData;
        }
      } else {
        req.body = {};
      }

      // Add status helper to res if missing
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
