import { Loader2 } from 'lucide-react';

interface LoadingSpinnerProps {
  className?: string;
}

export function LoadingSpinner({ className = 'h-64' }: LoadingSpinnerProps) {
  return (
    <div className={`flex items-center justify-center ${className}`}>
      <Loader2 className="text-accent h-8 w-8 animate-spin" />
    </div>
  );
}
