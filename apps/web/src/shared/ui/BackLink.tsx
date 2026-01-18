import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

interface BackLinkProps {
  to: string;
  label: string;
  'data-testid'?: string;
}

export function BackLink({ to, label, 'data-testid': testId }: BackLinkProps) {
  return (
    <Link
      data-testid={testId}
      to={to}
      className="text-text-secondary hover:text-text-primary inline-flex items-center gap-2 transition-colors"
    >
      <ArrowLeft className="h-4 w-4" />
      {label}
    </Link>
  );
}
