import { cn } from '@/shared/utils/cn';

type LoadErrorStateProps = Readonly<{
  message: string;
  onRetry: () => void;
  className?: string;
}>;

export function LoadErrorState({ message, onRetry, className }: LoadErrorStateProps) {
  return (
    <div
      data-testid="load-error"
      className={cn('flex flex-col items-center justify-center gap-3 py-8', className)}
    >
      <p className="text-text-secondary text-sm">{message}</p>
      <button
        type="button"
        onClick={onRetry}
        className="bg-bg-secondary text-text-primary hover:bg-bg-tertiary rounded-md px-4 py-2 text-sm transition-colors"
      >
        Retry
      </button>
    </div>
  );
}
