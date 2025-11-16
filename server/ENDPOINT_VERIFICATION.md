# Endpoint Verification Report

This document verifies that all backend endpoints match the frontend API expectations.

## Summary

**Total Endpoints Verified**: 26
**Status**: All endpoints have stub implementations with correct signatures
**Remaining Work**: Phase 1-7 implementations

---

## 1. Authentication Endpoints

### POST /Users/AuthenticateByName
**Frontend** (`packages/core/src/api/auth.ts:24-41`):
```typescript
const body = { Username: username, Pw: password }; // NOTE: "Pw" not "Password"
headers: { "X-Emby-Authorization": buildAuthHeader(clientInfo) }
```
**Backend** (`AuthController.java`):
- ✅ Accepts `Map<String, String>` body with "Pw" field
- ✅ Reads `X-Emby-Authorization` header
- ✅ Returns `AuthenticationResultDto` with User, SessionInfo, AccessToken, ServerId

### POST /Sessions/Logout
**Frontend** (`packages/core/src/api/auth.ts:58`):
```typescript
await this.client.post('/Sessions/Logout');
```
**Backend** (`AuthController.java`):
- ✅ Returns 204 No Content

### GET /Users/{userId}
**Frontend** (`packages/core/src/api/auth.ts:81`):
```typescript
await this.client.get(`/Users/${userId}`);
```
**Backend** (`AuthController.java`):
- ✅ Returns user info Map

---

## 2. Items Query Endpoints

### GET /Items
**Frontend** (`packages/core/src/api/items.ts:14-45`):
```typescript
const params = {
  userId,                    // lowercase
  ParentId,                  // PascalCase
  IncludeItemTypes,          // PascalCase
  Recursive,                 // PascalCase
  SortBy,                    // PascalCase
  SortOrder,                 // PascalCase
  StartIndex,                // PascalCase
  Limit,                     // PascalCase
  SearchTerm,                // PascalCase
  ArtistIds,                 // PascalCase, comma-separated
  AlbumIds,                  // PascalCase, comma-separated
  GenreIds,                  // PascalCase, comma-separated
  IsFavorite,                // PascalCase
  Fields,                    // PascalCase, comma-separated
  Filters,                   // PascalCase (e.g., "IsPlayed")
  enableUserData,            // camelCase
};
```
**Backend** (`ItemsController.java:30-64`):
- ✅ `userId` - correct case
- ✅ `ParentId` - correct case
- ✅ `IncludeItemTypes` - correct case
- ✅ `Recursive` - correct case
- ✅ `SortBy` - correct case
- ✅ `SortOrder` - correct case
- ✅ `StartIndex` - correct case
- ✅ `Limit` - correct case
- ✅ `SearchTerm` - correct case
- ✅ `ArtistIds` - correct case (String, comma-separated)
- ✅ `AlbumIds` - correct case (String, comma-separated)
- ✅ `GenreIds` - correct case (String, comma-separated)
- ✅ `IsFavorite` - correct case
- ✅ `Fields` - correct case (String, comma-separated)
- ✅ `Filters` - correct case
- ✅ `enableUserData` - correct case

### GET /Items/{itemId}
**Frontend** (`packages/core/src/api/items.ts:165-167`):
```typescript
await this.client.get(this.buildUserUrl(`/Items/${itemId}`))
// buildUserUrl returns: /Users/{userId}/Items/{itemId}
```
**Backend** (`UsersController.java:82-110`):
- ✅ GET `/Users/{userId}/Items/{itemId}` exists
- ✅ Returns `BaseItemDto` with proper fields

### DELETE /Items/{itemId}
**Frontend** (`packages/core/src/api/playlists.ts:131`):
```typescript
await this.client.delete(`/Items/${playlistId}`);
```
**Backend** (`ItemsController.java:153-166`):
- ✅ Accepts item ID path variable
- ✅ Returns 204 No Content

---

## 3. User-Specific Items

### GET /Users/{userId}/Items
**Frontend** (`packages/core/src/api/playlists.ts:143-159`):
```typescript
const params = {
  IncludeItemTypes: 'Playlist',
  Recursive: true,
  Fields: 'PrimaryImageAspectRatio,ChildCount',
  SortBy: 'SortName',
  SortOrder: 'Ascending',
  StartIndex,
  Limit,
};
```
**Backend** (`UsersController.java:36-73`):
- ✅ All parameters correct (PascalCase)
- ✅ Returns `QueryResultDto<BaseItemDto>`

### GET /Users/{userId}/Items/{itemId}
**Frontend** (`packages/core/src/api/playlists.ts:54`):
```typescript
await this.client.get(`/Users/${userId}/Items/${playlistId}`);
```
**Backend** (`UsersController.java:82-110`):
- ✅ Path variables match
- ✅ Returns `BaseItemDto`

---

## 4. Playlist Management

### POST /Playlists
**Frontend** (`packages/core/src/api/playlists.ts:17-40`):
```typescript
const body = {
  Name: string,
  Ids?: string[],        // Array of item IDs
  MediaType?: string,
  UserId: string,
  IsPublic?: boolean,
};
```
**Backend** (`PlaylistsController.java:48-62`):
- ✅ Accepts `Map<String, Object>` body
- ✅ Returns 201 Created with playlist object containing Id

### GET /Playlists/{playlistId}/Items
**Frontend** (`packages/core/src/api/playlists.ts:73-88`):
```typescript
const params = {
  UserId: userId,          // PascalCase
  StartIndex: number,      // PascalCase
  Limit: number,           // PascalCase
};
```
**Backend** (`PlaylistsController.java:112-132`):
- ✅ `UserId` - correct case
- ✅ `StartIndex` - correct case
- ✅ `Limit` - correct case
- ✅ `Fields` - available

### POST /Playlists/{playlistId}/Items
**Frontend** (`packages/core/src/api/playlists.ts:98-104`):
```typescript
const idsParam = itemIds.join(',');
const url = `/Playlists/${playlistId}/Items?Ids=${encodeURIComponent(idsParam)}&UserId=${userId}`;
await this.client.post(url, undefined);
```
**Backend** (`PlaylistsController.java:137-150`):
- ✅ `Ids` as comma-separated String
- ✅ `UserId` as String

### DELETE /Playlists/{playlistId}/Items
**Frontend** (`packages/core/src/api/playlists.ts:111-115`):
```typescript
const entryIdsParam = entryIds.join(',');
const url = `/Playlists/${playlistId}/Items?EntryIds=${encodeURIComponent(entryIdsParam)}`;
await this.client.delete(url);
```
**Backend** (`PlaylistsController.java:155-166`):
- ✅ `EntryIds` as comma-separated String

### POST /Playlists/{playlistId}/Items/{itemId}/Move/{newIndex}
**Frontend** (`packages/core/src/api/playlists.ts:124`):
```typescript
await this.client.post(`/Playlists/${playlistId}/Items/${itemId}/Move/${newIndex}`);
```
**Backend** (`PlaylistsController.java:170-180`):
- ✅ All path variables match

---

## 5. Favorites Endpoints

### POST /Items/{itemId}/Favorite
**Frontend** (not yet implemented in frontend service, but used in UI):
```typescript
await this.client.post(`/Items/${itemId}/Favorite`);
```
**Backend** (`ItemsController.java:123-131`):
- ✅ Returns 204 No Content

### DELETE /Items/{itemId}/Favorite
**Frontend**:
```typescript
await this.client.delete(`/Items/${itemId}/Favorite`);
```
**Backend** (`ItemsController.java:135-143`):
- ✅ Returns 204 No Content

---

## 6. Playback Sessions

### POST /Sessions/Playing
**Frontend** (`packages/core/src/player/state.ts:45, 243`):
```typescript
const info = {
  ItemId: string,
  PositionTicks: number,    // Ticks (1 second = 10,000,000 ticks)
  IsPaused: boolean,
  PlayMethod: string,
  PlaySessionId: string,
};
await this.client.post('/Sessions/Playing', info);
```
**Backend** (`SessionsController.java:27-36`):
- ✅ Accepts `Map<String, Object>` body
- ✅ Returns 204 No Content

### POST /Sessions/Playing/Progress
**Frontend** (`packages/core/src/player/state.ts:65, 268`):
```typescript
const info = {
  ItemId: string,
  PositionTicks: number,
  IsPaused: boolean,
  PlayMethod: string,
  PlaySessionId: string,
};
await this.client.post('/Sessions/Playing/Progress', info);
```
**Backend** (`SessionsController.java:44-53`):
- ✅ Accepts `Map<String, Object>` body
- ✅ Returns 204 No Content

### POST /Sessions/Playing/Stopped
**Frontend** (`packages/core/src/player/state.ts:81, 289`):
```typescript
const info = {
  ItemId: string,
  PositionTicks: number,
  PlaySessionId: string,
};
await this.client.post('/Sessions/Playing/Stopped', info);
```
**Backend** (`SessionsController.java:62-71`):
- ✅ Accepts `Map<String, Object>` body
- ✅ Returns 204 No Content

---

## 7. Streaming

### GET /Audio/{itemId}/stream
**Frontend** (`packages/core/src/api/client.ts:516-530`):
```typescript
const params = {
  api_key: token,           // lowercase with underscore
  deviceId: deviceId,       // camelCase
  audioCodec: 'aac,mp3,opus',  // camelCase
  container: 'opus,mp3,aac,m4a,flac',
  static: 'true',           // lowercase
  audioBitRate?: number,    // camelCase (optional)
};
// Browser sends Range header for seeking
```
**Backend** (`StreamingController.java:38-67`):
- ✅ `api_key` - correct case
- ✅ `deviceId` - correct case
- ✅ `audioCodec` - correct case
- ✅ `container` - correct case
- ✅ `static` - correct case
- ✅ `audioBitRate` - correct case
- ✅ Reads `Range` header for byte-range support

---

## 8. Images

### GET /Items/{itemId}/Images/{imageType}
**Frontend** (`packages/core/src/api/client.ts:463-500`):
```typescript
const params = {
  tag?: string,             // camelCase
  maxWidth?: number,        // camelCase
  maxHeight?: number,       // camelCase
  quality?: number,         // camelCase
  format?: string,          // camelCase
};
```
**Backend** (`ImagesController.java`):
- ✅ Accepts all parameters
- ✅ Returns image with proper Content-Type

---

## 9. System Info

### GET /System/Info/Public
**Frontend** (`packages/core/src/api/client.ts:537`):
```typescript
await this.get('/System/Info/Public');
```
**Backend** (`SystemController.java`):
- ✅ No auth required
- ✅ Returns server info Map with ServerName, Version, Id, etc.

---

## Response Format Verification

### BaseItemDto Critical Fields
**Backend** (`BaseItemDto.java:39-44`):
```java
@JsonProperty("IndexNumber") Integer indexNumber      // Track number
@JsonProperty("ParentIndexNumber") Integer parentIndexNumber  // Disc number
@JsonProperty("RunTimeTicks") Long runTimeTicks       // TICKS not milliseconds!
```
**Conversion** (`BaseItemDto.java:222`):
```java
Long runTimeTicks = durationMs != null ? durationMs * 10000L : null;
```
- ✅ Correctly converts milliseconds to ticks (10,000 ticks = 1ms, 10,000,000 ticks = 1 second)

### AuthenticationResultDto
- ✅ Contains User (UserDto), SessionInfo (SessionInfoDto), AccessToken, ServerId
- ✅ All fields use PascalCase via @JsonProperty

### QueryResultDto
- ✅ Contains Items, TotalRecordCount, StartIndex
- ✅ Uses PascalCase for JSON serialization

---

## Implementation Status by Phase

| Phase | Feature | Endpoints | Status |
|-------|---------|-----------|--------|
| 0 | Skeleton | All | ✅ Stub implementations |
| 1 | Library Scanning | Items, Users | ⏳ Pending |
| 2 | Authentication | AuthenticateByName, Logout | ⏳ Pending |
| 3 | Images | /Items/{id}/Images/{type} | ⏳ Pending |
| 4 | Direct Streaming | /Audio/{id}/stream | ⏳ Pending |
| 5 | Transcoding | /Audio/{id}/stream (codec) | ⏳ Pending |
| 6 | Playback Reporting | Sessions/Playing/* | ⏳ Pending |
| 7 | Playlists | /Playlists/* | ⏳ Pending |

---

## Critical Implementation Notes

1. **Time Units**: Always use TICKS (10,000,000 ticks = 1 second), never milliseconds
2. **Field Naming**: Use PascalCase for JSON (via @JsonProperty), not camelCase
3. **Password Field**: Use "Pw" not "Password" in auth request body
4. **Comma-Separated Params**: ArtistIds, AlbumIds, GenreIds, Fields, Ids, EntryIds are comma-separated strings
5. **Stream Authentication**: Uses `api_key` query parameter (not header) for `<audio>` element compatibility
6. **User Data**: When `enableUserData=true`, include play count, favorite status, last played date
