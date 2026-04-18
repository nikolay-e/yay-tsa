rootProject.name = "yaytsa"

// Shared foundation
include("core-domain:shared")
include("core-application:shared")
include("infra-persistence:shared")

// Per-context: core-domain
include("core-domain:auth")
include("core-domain:library")
include("core-domain:playback")
include("core-domain:adaptive")
include("core-domain:preferences")
include("core-domain:playlists")
include("core-domain:ml")
include("core-domain:karaoke")

// Per-context: core-application
include("core-application:auth")
include("core-application:library")
include("core-application:playback")
include("core-application:adaptive")
include("core-application:preferences")
include("core-application:playlists")
include("core-application:ml")
include("core-application:karaoke")

// Per-context: infra-persistence
include("infra-persistence:auth")
include("infra-persistence:library")
include("infra-persistence:playback")
include("infra-persistence:adaptive")
include("infra-persistence:preferences")
include("infra-persistence:playlists")
include("infra-persistence:ml")
include("infra-persistence:karaoke")

// Workers (write-side for read-only collections)
include("infra-library-scanner")
include("infra-ml-worker")
include("infra-karaoke-worker")
include("infra-llm")

// Existing infrastructure
include("infra-media")
include("infra-notifications")

// Test support
include("core-testkit")

// Adapters
include("adapter-opensubsonic")
include("adapter-jellyfin")
include("adapter-mcp")
include("adapter-mpd")

// Composition root
include("app")
