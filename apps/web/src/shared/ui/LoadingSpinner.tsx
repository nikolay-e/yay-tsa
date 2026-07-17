import { Loader2 } from 'lucide-react';

type LoadingSpinnerProps = Readonly<{
  className?: string;
}>;

export function LoadingSpinner({ className = 'h-64' }: LoadingSpinnerProps) {
  return (
    <output aria-label="Loading" className={`flex items-center justify-center ${className}`}>
      <Loader2 aria-hidden="true" className="text-accent h-8 w-8 animate-spin" />
    </output>
  );
}
