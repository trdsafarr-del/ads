# Implementation Plan - Web Dashboard Upload & Deletion Persistence Fix

## User Problem
When deleting a video in the web dashboard, it appears deleted initially, but upon refreshing the page, the deleted video (or demo videos) re-appears.

## Root Cause Analysis
1. **Cloud State Precedence**: The server (`loadStateAsync`) was prioritizing remote Firestore state version/timestamp over local state updates if remote version numbers clashed or stale Firestore documents weren't updated instantly.
2. **Sync Persistence**: Deletions and uploads needed immediate local timestamp prioritization and robust state synchronization across both `/api/playlist` and Firestore global state endpoints.

## Proposed Changes
1. **Server State Merging (`api/_store.js`)**:
   - Updated `loadStateAsync` so that local state updates (such as video deletions and new uploads) take immediate precedence when local version or `updatedAtMs` is newer or equal to remote state.
2. **Dashboard Deletion Action (`public/index.html`)**:
   - Enhanced `removeVideoItem` to ensure playlist changes are fully published, persisted in `localStorage`, synced to server and Firestore, and confirmed with a success toast.
3. **Storage & Cleanup**:
   - Ensured uploaded videos are properly stored in the `/tmp/videos` folder and deleted videos are permanently removed from state persistence.

## Verification Plan
- Compile applet and verify successful build.
- Verify state persistence across page refreshes in the web dashboard.
