# Metadata Enrichment Providers

The server supports automatic metadata enrichment for uploaded tracks with missing album information. Multiple providers are queried **in parallel** and the best result is selected based on confidence scoring.

## Supported Providers

### 1. Genius (Recommended for Russian Artists)
- **Status**: ⚠️ Requires Access Token
- **API Key**: Free at https://genius.com/api-clients
- **Coverage**: Excellent for modern music (2010+), **very good for Russian artists**
- **Rate Limit**: No hard limit
- **Priority**: 75
- **Special**: Also provides lyrics, verified annotations

**Configuration:**
```bash
# Environment variable
export GENIUS_ACCESS_TOKEN=your_access_token_here

# Or in application.yml
yaytsa:
  metadata:
    genius:
      access-token: your_access_token_here
```

### 2. MusicBrainz (Always Enabled)
- **Status**: ✅ Enabled by default
- **API Key**: Not required
- **Coverage**: Excellent for mainstream music, some Russian artists
- **Rate Limit**: 1 request/second
- **Priority**: 60

### 3. Last.fm
- **Status**: ⚠️ Requires API key
- **API Key**: Free at https://www.last.fm/api/account/create
- **Coverage**: Excellent for all music, especially popular tracks
- **Rate Limit**: No hard limit with API key
- **Priority**: 70

**Configuration:**
```bash
# Environment variable
export LASTFM_API_KEY=your_api_key_here

# Or in application.yml
yaytsa:
  metadata:
    lastfm:
      api-key: your_api_key_here
```

### 4. Spotify
- **Status**: ⚠️ Requires Client ID & Secret
- **API Key**: Free app at https://developer.spotify.com/dashboard
- **Coverage**: Best for modern music (2010+), all genres
- **Rate Limit**: OAuth token-based
- **Priority**: 80 (highest)

**Configuration:**
```bash
# Environment variables
export SPOTIFY_CLIENT_ID=your_client_id
export SPOTIFY_CLIENT_SECRET=your_client_secret

# Or in application.yml
yaytsa:
  metadata:
    spotify:
      client-id: your_client_id
      client-secret: your_client_secret
```

## How It Works

1. **Parallel Queries**: All enabled providers are queried simultaneously using Java 21 virtual threads
2. **Confidence Scoring**: Each result gets a score (0.0-1.0) based on:
   - Artist name match quality (40% weight)
   - Track title match quality (60% weight)
   - Additional factors (popularity, album availability)
3. **Provider Priority**: Results are weighted by provider priority (higher = preferred)
4. **Best Selection**: `score = confidence × (priority / 100)`
5. **Caching**: Results are cached for 7 days to reduce API calls

## Scoring Example

For track "прыгай, дура!" by CUPSIZE:

```
Provider: Spotify
  - Confidence: 0.95 (exact match)
  - Priority: 80
  - Score: 0.95 × 0.80 = 0.76

Provider: MusicBrainz
  - Confidence: 0.85 (good match)
  - Priority: 60
  - Score: 0.85 × 0.60 = 0.51

→ Selected: Spotify (higher score)
```

## API Key Setup Guide

### Genius Access Token (Best for Russian Music)

1. Go to https://genius.com/api-clients
2. Sign in or create account
3. Click **New API Client**
4. Fill in details:
   - **App Name**: Yay-Tsa Media Server
   - **App Website URL**: http://localhost (or your domain)
   - **Redirect URI**: http://localhost (not used)
5. After creation, click **Generate Access Token**
6. Copy the **Access Token** (starts with `_` or long alphanumeric)
7. Set environment variable `GENIUS_ACCESS_TOKEN`

### Last.fm API Key (Recommended)

1. Go to https://www.last.fm/api/account/create
2. Fill in application details:
   - **Application name**: Yay-Tsa Media Server
   - **Description**: Personal media server with metadata enrichment
   - **Callback URL**: (leave empty)
3. Copy **API Key** (NOT the Shared Secret)
4. Set environment variable or update config

### Spotify API (Best for Modern Music)

1. Go to https://developer.spotify.com/dashboard
2. Click **Create App**
3. Fill in details:
   - **App name**: Yay-Tsa Media Server
   - **App description**: Metadata enrichment for personal use
   - **Redirect URI**: http://localhost (not used, but required)
   - **API/SDKs**: Select "Web API"
4. After creation, go to **Settings**
5. Copy **Client ID** and **Client Secret**
6. Set both environment variables or update config

## Testing

After configuring API keys, restart the server and check logs:

```bash
grep "Initialized metadata enrichment" logs/yay-tsa.log
```

Expected output:
```
Initialized metadata enrichment with 4 providers: [Genius, MusicBrainz, Last.fm, Spotify]
```

Upload a track without album metadata and check:

```bash
grep "Querying.*providers" logs/yay-tsa.log
grep "Selected best match" logs/yay-tsa.log
```

## Troubleshooting

**No providers enabled:**
```
WARN: No metadata providers are enabled
```
→ Configure at least Last.fm or Spotify API keys

**Provider query failed:**
```
WARN: Last.fm query failed: 401 Unauthorized
```
→ Check API key is correct

**Spotify authentication failed:**
```
ERROR: Failed to obtain Spotify access token
```
→ Verify both Client ID and Client Secret are correct

## Performance

- **Parallel execution**: All providers query simultaneously (~1-2 seconds total)
- **Caching**: 7-day cache per provider (avoid repeated queries)
- **Rate limiting**: MusicBrainz enforced at 1 req/sec, others have higher limits
- **Fallback**: If no providers find a match, uses "Unknown Album"

## Future Providers

Potential additions:
- Discogs API (discography database)
- AcoustID (audio fingerprinting)
- MusicGraph API
- Deezer API
