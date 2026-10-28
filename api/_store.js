const fs = require('fs');
const path = require('path');

const STATE_FILE = path.join('/tmp', 'signage_cms_state_v2.json');
const UPLOAD_DIR = path.join('/tmp', 'signage_uploads');

try {
  if (!fs.existsSync(UPLOAD_DIR)) {
    fs.mkdirSync(UPLOAD_DIR, { recursive: true });
  }
} catch (e) {
  // ignore in read-only environments
}

const SAMPLE_VAST_TAG =
  'https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_ad_samples&sz=640x480&cust_params=sample_ct%3Dlinear&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&impl=s&correlator=';

const RELIABLE_URLS = [
  'https://www.w3schools.com/html/mov_bbb.mp4',
  'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4',
  'https://media.w3.org/2010/05/sintel/trailer.mp4',
  'https://media.w3.org/2010/05/bunny/trailer.mp4'
];

const DEFAULT_STATE = {
  autoApprovePairing: true,
  playlist: {
    version: 1,
    orientation: 'LANDSCAPE',
    scaleMode: 'ZOOM',
    tickerEnabled: true,
    isMuted: false,
    kioskLocked: true,
    autoBootEnabled: true,
    lastPushedVideoId: null,
    lastPushTimestamp: 0,
    offlineDownloadRequestedVideoId: null,
    tickerText:
      '★ LIVE DIGITAL SIGNAGE (ads-broo.vercel.app) ★ Continuous Gapless Loop • Instant Push Auto-Play • Offline Video Caching • 24/7 Watchdog Recovery',
    ads: [
      {
        vastTag: SAMPLE_VAST_TAG,
        frequency: 2
      }
    ],
    schedule: [
      {
        startTime: '00:00',
        endTime: '23:59',
        videoIds: ['vid_bunny', 'vid_dream', 'vid_blazes', 'vid_bullrun']
      }
    ],
    videos: [
      {
        id: 'vid_bunny',
        title: 'Brand Showcase: Nature & Animation',
        url: RELIABLE_URLS[0],
        order: 1,
        duration: 60,
        checksum: null,
        adTagUrl: SAMPLE_VAST_TAG
      },
      {
        id: 'vid_dream',
        title: 'Digital Arts & 3D Render Feature',
        url: RELIABLE_URLS[1],
        order: 2,
        duration: 45,
        checksum: null,
        adTagUrl: null
      },
      {
        id: 'vid_blazes',
        title: 'High Energy Automotive Commercial',
        url: RELIABLE_URLS[2],
        order: 3,
        duration: 30,
        checksum: null,
        adTagUrl: SAMPLE_VAST_TAG
      },
      {
        id: 'vid_bullrun',
        title: 'Summer Road Trip Experience',
        url: RELIABLE_URLS[3],
        order: 4,
        duration: 40,
        checksum: null,
        adTagUrl: null
      }
    ]
  },
  approvedCodes: {},
  devices: {},
  queuedActions: {}
};

function sanitizeUrl(url, index) {
  if (!url || String(url).includes('commondatastorage.googleapis.com/gtv-videos-bucket')) {
    return RELIABLE_URLS[index % RELIABLE_URLS.length];
  }
  return url;
}

function loadState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      const raw = fs.readFileSync(STATE_FILE, 'utf8');
      const parsed = JSON.parse(raw);
      const merged = {
        ...DEFAULT_STATE,
        ...parsed,
        playlist: {
          ...DEFAULT_STATE.playlist,
          ...(parsed.playlist || {})
        }
      };
      if (Array.isArray(merged.playlist.videos)) {
        merged.playlist.videos = merged.playlist.videos.map((v, idx) => ({
          ...v,
          url: sanitizeUrl(v.url, idx)
        }));
      }
      return merged;
    }
  } catch (e) {
    // fallback to default state
  }
  return JSON.parse(JSON.stringify(DEFAULT_STATE));
}

function saveState(state) {
  try {
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2), 'utf8');
  } catch (e) {
    // ignore write errors in read-only environments
  }
  return state;
}

function setCors(res) {
  res.setHeader('Access-Control-Allow-Credentials', 'true');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS,PATCH,DELETE,POST,PUT');
  res.setHeader(
    'Access-Control-Allow-Headers',
    'X-CSRF-Token, X-Requested-With, Accept, Accept-Version, Content-Length, Content-MD5, Content-Type, Date, X-Api-Version'
  );
}

module.exports = {
  loadState,
  saveState,
  setCors,
  SAMPLE_VAST_TAG,
  RELIABLE_URLS,
  UPLOAD_DIR
};
