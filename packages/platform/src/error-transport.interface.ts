export type ClientErrorCategory =
  | 'runtime'
  | 'promise'
  | 'react'
  | 'network'
  | 'audio'
  | 'sw'
  | 'resource'
  | 'other';

export interface ClientErrorReport {
  category: ClientErrorCategory;
  type: string;
  message: string;
  appVersion: string;
  telemetrySessionId: string;
  fingerprint?: string;
  count?: number;
  route?: string;
  stack?: string;
  uaReduced?: string;
  http?: {
    status?: number;
    method?: string;
    route?: string;
  };
  audio?: {
    state?: string;
    mediaError?: number | null;
    readyState?: number;
    networkState?: number;
  };
}

export interface ErrorTransport {
  send(report: ClientErrorReport): void;
}
