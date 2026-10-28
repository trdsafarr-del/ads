# Digital Signage CMS Dashboard Architecture & Supabase Backend

This document specifies the companion full-stack CMS Dashboard built with Next.js 14 (App Router) + Supabase (PostgreSQL, Storage, Auth) + Tailwind CSS, designed to manage the Android TV digital signage fleet remotely.

---

## 1. Supabase PostgreSQL Schema with Row Level Security (RLS)

```sql
-- Enable UUID extension
create extension if not exists "uuid-ossp";

-- 1. Organizations (Multi-tenant)
create table organizations (
    id uuid primary key default uuid_generate_v4(),
    name text not null,
    created_at timestamp with time zone default now()
);

-- 2. Organization Members & Roles (OWNER, ADMIN, VIEWER)
create type user_role as enum ('OWNER', 'ADMIN', 'VIEWER');

create table organization_members (
    id uuid primary key default uuid_generate_v4(),
    org_id uuid references organizations(id) on delete cascade not null,
    user_id uuid references auth.users(id) on delete cascade not null,
    role user_role default 'VIEWER' not null,
    created_at timestamp with time zone default now(),
    unique(org_id, user_id)
);

-- 3. Videos Library (Supabase Storage bucket: 'signage-videos')
create table videos (
    id uuid primary key default uuid_generate_v4(),
    org_id uuid references organizations(id) on delete cascade not null,
    name text not null,
    storage_path text not null,
    url text not null,
    thumbnail_url text,
    duration_seconds integer default 30 not null,
    size_bytes bigint not null,
    checksum text,
    tags text[] default array[]::text[],
    created_at timestamp with time zone default now()
);

-- 4. Playlists
create table playlists (
    id uuid primary key default uuid_generate_v4(),
    org_id uuid references organizations(id) on delete cascade not null,
    name text not null,
    version integer default 1 not null,
    default_ad_tag text,
    ad_frequency integer default 2,
    ticker_text text,
    created_at timestamp with time zone default now(),
    updated_at timestamp with time zone default now()
);

-- 5. Playlist Items (Ordered media)
create table playlist_items (
    id uuid primary key default uuid_generate_v4(),
    playlist_id uuid references playlists(id) on delete cascade not null,
    video_id uuid references videos(id) on delete cascade not null,
    sort_order integer default 0 not null,
    duration_override integer,
    skip boolean default false,
    ad_tag_url text
);

-- 6. Devices (Android TV Signage Players)
create type device_status as enum ('ONLINE', 'OFFLINE', 'PAIRING', 'MAINTENANCE');

create table devices (
    id uuid primary key default uuid_generate_v4(),
    org_id uuid references organizations(id) on delete cascade,
    device_id text unique not null,
    name text default 'New Android TV Display' not null,
    pairing_code text,
    pairing_code_expires_at timestamp with time zone,
    device_token text unique,
    active_playlist_id uuid references playlists(id) on delete set null,
    status device_status default 'PAIRING' not null,
    current_video_id text,
    storage_used_bytes bigint default 0,
    app_version text,
    ip_address text,
    last_heartbeat_at timestamp with time zone,
    created_at timestamp with time zone default now()
);

-- 7. Device Logs (Incident reports and telemetry)
create table device_logs (
    id bigint generated always as identity primary key,
    device_id text not null,
    level text not null,
    tag text not null,
    message text not null,
    created_at timestamp with time zone default now()
);

-- 8. Device Screenshots (Live preview)
create table device_screenshots (
    id uuid primary key default uuid_generate_v4(),
    device_id text not null,
    screenshot_url text not null,
    created_at timestamp with time zone default now()
);

-- 9. Schedules
create table schedules (
    id uuid primary key default uuid_generate_v4(),
    org_id uuid references organizations(id) on delete cascade not null,
    playlist_id uuid references playlists(id) on delete cascade not null,
    start_time text default '09:00' not null,
    end_time text default '21:00' not null,
    days_of_week integer[] default array[1,2,3,4,5,6,7],
    created_at timestamp with time zone default now()
);

-- 10. Play Logs (Proof-of-play analytics)
create table play_logs (
    id bigint generated always as identity primary key,
    device_id text not null,
    video_id text not null,
    duration_played integer not null,
    completed boolean default true,
    created_at timestamp with time zone default now()
);

-- ==========================================
-- ROW LEVEL SECURITY (RLS) POLICIES
-- ==========================================

alter table organizations enable row level security;
alter table organization_members enable row level security;
alter table videos enable row level security;
alter table playlists enable row level security;
alter table playlist_items enable row level security;
alter table devices enable row level security;
alter table schedules enable row level security;

-- Helper to check organization membership
create or replace function is_org_member(org_uuid uuid)
returns boolean as $$
    select exists (
        select 1 from organization_members
        where org_id = org_uuid and user_id = auth.uid()
    );
$$ language sql security definer;

-- RLS: Organizations
create policy "Org members can read organization" on organizations
    for select using (is_org_member(id));

-- RLS: Videos
create policy "Org members can manage videos" on videos
    for all using (is_org_member(org_id));

-- RLS: Playlists
create policy "Org members can manage playlists" on playlists
    for all using (is_org_member(org_id));

-- RLS: Devices
create policy "Org members can view and manage devices" on devices
    for all using (org_id is null or is_org_member(org_id));
```

---

## 2. Next.js 14 API Route Endpoints

### `POST /api/devices/pair`
Called by the Android TV player on first boot:
- **Request Body**:
  ```json
  {
    "deviceId": "TV-4F29B81A",
    "pairingCode": "482910",
    "deviceInfo": "Android TV 12 (2GB RAM)"
  }
  ```
- **Response**:
  ```json
  {
    "success": true,
    "deviceToken": "sec_tok_84f920da418e2",
    "organizationId": "550e8400-e29b-41d4-a716-446655440000"
  }
  ```

### `POST /api/devices/heartbeat`
Called every 5 minutes by the Android TV player:
- **Request Body**:
  ```json
  {
    "deviceId": "TV-4F29B81A",
    "status": "PLAYING",
    "currentVideoId": "vid_bunny",
    "storageUsed": 154829102,
    "appVersion": "1.0",
    "lastSyncTime": 1730114000000
  }
  ```
- **Response**:
  ```json
  {
    "acknowledged": true,
    "serverAction": null
  }
  ```

### `GET /api/playlist?deviceId=TV-4F29B81A`
Returns the scheduled playlist payload:
```json
{
  "version": 4,
  "videos": [
    {
      "id": "vid_bunny",
      "url": "https://storage.example.com/videos/bunny.mp4",
      "order": 1,
      "duration": 60,
      "checksum": "a1b2c3d4e5f6..."
    }
  ],
  "ads": [
    {
      "vastTag": "https://pubads.g.doubleclick.net/gampad/ads?...",
      "frequency": 2
    }
  ],
  "schedule": [
    {
      "startTime": "09:00",
      "endTime": "21:00",
      "videoIds": ["vid_bunny"]
    }
  ]
}
```

---

## 3. Environment Variables (`.env.local`)

```env
NEXT_PUBLIC_SUPABASE_URL=https://your-project.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=eyJhbGciOi...
SUPABASE_SERVICE_ROLE_KEY=eyJhbGciOi...
NEXT_PUBLIC_APP_URL=https://signage.yourdomain.com
```
