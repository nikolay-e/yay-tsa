import { useNavigate } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

type BackLinkProps = Readonly<{
  to: string;
  label: string;
  'data-testid'?: string;
}>;

export function BackLink({ to, label, 'data-testid': testId }: BackLinkProps) {
  const navigate = useNavigate();

  const handleClick = () => {
    const historyIndex = (window.history.state as { idx?: number } | null)?.idx ?? 0;
    if (historyIndex > 0) {
      navigate(-1);
    } else {
      navigate(to);
    }
  };

  return (
    <button
      data-testid={testId}
      type="button"
      onClick={handleClick}
      className="text-text-secondary hover:text-text-primary inline-flex cursor-pointer items-center gap-2 transition-colors"
    >
      <ArrowLeft className="h-4 w-4" />
      {label}
    </button>
  );
}
