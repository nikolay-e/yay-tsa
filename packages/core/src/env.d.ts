/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_JELLYFIN_API_KEY?: string;
  readonly VITE_JELLYFIN_SERVER_URL?: string;
  readonly VITE_JELLYFIN_CLIENT_NAME?: string;
  readonly VITE_JELLYFIN_DEVICE_NAME?: string;
  readonly VITE_JELLYFIN_DEVICE_ID?: string;
}

interface ImportMeta {
  readonly env?: ImportMetaEnv | Record<string, string | undefined>;
}
