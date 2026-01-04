import { cn } from '@/shared/utils/cn';

type Environment = 'production' | 'staging' | 'development';

const environment = ((import.meta.env.VITE_APP_ENVIRONMENT as string | undefined) ??
  'development') as Environment;
const version = (import.meta.env.VITE_APP_VERSION as string | undefined) ?? 'dev';

const envStyles: Record<Environment, string> = {
  production: 'bg-success text-white',
  staging: 'bg-warning text-black',
  development: 'bg-bg-tertiary text-text-secondary',
};

export function VersionInfo() {
  return (
    <div className="flex items-center justify-center gap-2 py-2 font-mono text-xs opacity-60 transition-opacity hover:opacity-100">
      <span className={cn('rounded px-2 py-0.5', envStyles[environment])}>{environment}</span>
      <span className="bg-accent rounded px-2 py-0.5 text-white">v{version}</span>
    </div>
  );
}
