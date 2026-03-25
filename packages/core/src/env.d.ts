/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_YAYTSA_SERVER_URL?: string;
  readonly VITE_YAYTSA_CLIENT_NAME?: string;
  readonly VITE_YAYTSA_DEVICE_NAME?: string;
  readonly VITE_YAYTSA_DEVICE_ID?: string;
}

interface ImportMeta {
  readonly env?: ImportMetaEnv | Record<string, string | undefined>;
}

declare let process:
  | {
      env: Record<string, string | undefined>;
      [key: string]: unknown;
    }
  | undefined;
