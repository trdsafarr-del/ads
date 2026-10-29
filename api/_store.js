const fs = require('fs');
const path = require('path');

const STATE_FILE = path.join('/tmp', 'signage_cms_state_v5.json');
const UPLOAD_DIR = path.join('/tmp', 'signage_uploads');

const FIRESTORE_PROJECT_ID = 'gen-lang-client-0563270290';
const FIRESTORE_DATABASE_ID = 'ai-studio-android-signaget-4a0ce43d-8b5a-43d1-8423-f48d2b432ace';
const FIRESTORE_BASE_URL = `https://firestore.googleapis.com/v1/projects/${FIRESTORE_PROJECT_ID}/databases/${FIRESTORE_DATABASE_ID}/documents`;
const FIRESTORE_STATE_DOC_URL = `${FIRESTORE_BASE_URL}/cms_global/live_state`;

try {
  if (!fs.existsSync(UPLOAD_DIR)) {
    fs.mkdirSync(UPLOAD_DIR, { recursive: true });
  }
} catch (e) {}

const SAMPLE_VAST_TAG =
  'https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_ad_samples&sz=640x480&cust_params=sample_ct%3Dlinear&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&impl=s&correlator=';

const RELIABLE_URLS = [
  'https://www.w3schools.com/html/mov_bbb.mp4',
  'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4',
  'https://media.w3.org/2010/05/sintel/trailer.mp4',
  'https://media.w3.org/2010/05/bunny/trailer.mp4'
];

const DEFAULT_STATE = {
  autoApprovePairing: false,
  updatedAtMs: 0,
  playlist: {
    version: 1,
    orientation: 'LANDSCAPE',
    videoOrientation: 0,
    scaleMode: 'ZOOM',
    tickerEnabled: false,
    isMuted: false,
    kioskLocked: true,
    autoBootEnabled: true,
    lastPushedVideoId: null,
    lastPushTimestamp: 0,
    offlineDownloadRequestedVideoId: null,
    activeVideoIds: null,
    tickerText: '',
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
        adTagUrl: null
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
        adTagUrl: null
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
  queuedActions: {},
  uploadedVideos: {}
};

const MOCK_VIDEO_IDS = new Set(['vid_bunny', 'vid_dream', 'vid_blazes', 'vid_bullrun']);

function sanitizeUrl(url, index, videoId, uploadedVideos) {
  const s = String(url || '').trim();
  if (!s || s.includes('commondatastorage.googleapis.com/gtv-videos-bucket')) {
    return RELIABLE_URLS[index % RELIABLE_URLS.length];
  }
  // If it's a tmpfiles.org link, convert to direct download / stream link if needed
  if (s.includes('tmpfiles.org')) {
    if (s.includes('tmpfiles.org/dl/')) {
      return s;
    }
    return s.replace('tmpfiles.org/', 'tmpfiles.org/dl/');
  }
  return s;
}

function normalizeParsedState(parsed) {
  const merged = {
    ...DEFAULT_STATE,
    ...(parsed || {}),
    playlist: {
      ...DEFAULT_STATE.playlist,
      ...((parsed && parsed.playlist) || {})
    },
    approvedCodes: {
      ...((parsed && parsed.approvedCodes) || {})
    },
    devices: {
      ...((parsed && parsed.devices) || {})
    },
    queuedActions: {
      ...((parsed && parsed.queuedActions) || {})
    },
    uploadedVideos: {
      ...((parsed && parsed.uploadedVideos) || {})
    }
  };
  if (Array.isArray(parsed && parsed.playlist && parsed.playlist.videos)) {
    merged.playlist.videos = parsed.playlist.videos.map((v, idx) => ({
      ...v,
      url: sanitizeUrl(v.url, idx, v.id, merged.uploadedVideos)
    }));
  } else if (Array.isArray(merged.playlist.videos)) {
    merged.playlist.videos = merged.playlist.videos.map((v, idx) => ({
      ...v,
      url: sanitizeUrl(v.url, idx, v.id, merged.uploadedVideos)
    }));
  }
  return merged;
}

function loadState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      const raw = fs.readFileSync(STATE_FILE, 'utf8');
      return normalizeParsedState(JSON.parse(raw));
    }
  } catch (e) {}
  return JSON.parse(JSON.stringify(DEFAULT_STATE));
}

function saveState(state) {
  state.updatedAtMs = Date.now();
  try {
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2), 'utf8');
  } catch (e) {}
  return state;
}

async function loadStateAsync() {
  const localState = loadState();
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 3500);
    const res = await fetch(FIRESTORE_STATE_DOC_URL, {
      method: 'GET',
      signal: controller.signal
    });
    clearTimeout(timer);
    if (res.ok) {
      const doc = await res.json();
      const rawJson = doc && doc.fields && doc.fields.stateJson && doc.fields.stateJson.stringValue;
      const remoteUpdatedMs = Number(
        (doc && doc.fields && doc.fields.updatedAtMs && doc.fields.updatedAtMs.integerValue) || 0
      );
      if (rawJson) {
        const remoteState = normalizeParsedState(JSON.parse(rawJson));
        remoteState.updatedAtMs = remoteUpdatedMs || remoteState.updatedAtMs || 0;
        const localVideos = (localState.playlist && localState.playlist.videos) || [];
        const remoteVideos = (remoteState.playlist && remoteState.playlist.videos) || [];
        const localHasCustom = localVideos.some(v => v && v.id && !MOCK_VIDEO_IDS.has(v.id));
        const remoteHasCustom = remoteVideos.some(v => v && v.id && !MOCK_VIDEO_IDS.has(v.id));

        // If local disk has custom user videos and remote doc only has default mock videos, protect user videos!
        if (localHasCustom && !remoteHasCustom) {
          return localState;
        }

        const localVer = Number(localState.playlist && localState.playlist.version) || 1;
        const remoteVer = Number(remoteState.playlist && remoteState.playlist.version) || 1;

        if (
          remoteVer >= localVer ||
          remoteUpdatedMs >= (Number(localState.updatedAtMs) || 0)
        ) {
          try {
            fs.writeFileSync(STATE_FILE, JSON.stringify(remoteState, null, 2), 'utf8');
          } catch (e) {}
          return remoteState;
        }
      }
    }
  } catch (e) {}
  return localState;
}

async function saveStateAsync(state) {
  const nowMs = Date.now();
  state.updatedAtMs = nowMs;
  saveState(state);

  // Strip any oversized base64 fields from stateJson before saving to Firestore cms_global/live_state
  const slimState = {
    ...state,
    uploadedVideos: {}
  };
  if (state.uploadedVideos) {
    Object.keys(state.uploadedVideos).forEach((k) => {
      const item = state.uploadedVideos[k];
      if (item) {
        slimState.uploadedVideos[k] = {
          videoId: item.videoId,
          filename: item.filename,
          sizeBytes: item.sizeBytes,
          totalChunks: item.totalChunks || 0,
          cdnUrl: item.cdnUrl || null,
          url: item.url,
          uploadedAt: item.uploadedAt
        };
      }
    });
  }

  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 1500);
    await fetch(FIRESTORE_STATE_DOC_URL, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fields: {
          stateJson: { stringValue: JSON.stringify(slimState) },
          updatedAtMs: { integerValue: String(nowMs) }
        }
      }),
      signal: controller.signal
    });
    clearTimeout(timer);
  } catch (e) {}
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
  loadStateAsync,
  saveStateAsync,
  setCors,
  SAMPLE_VAST_TAG,
  RELIABLE_URLS,
  UPLOAD_DIR,
  FIRESTORE_BASE_URL
};
