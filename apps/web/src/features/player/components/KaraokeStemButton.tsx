import { MicVocal, Music2 } from 'lucide-react';

interface KaraokeStemButtonProps {
  active: boolean;
  stem: 'instrumental' | 'vocals';
  disabled: boolean;
  onToggle: () => void;
}

// Switches the served karaoke stem between instrumental (vocals removed) and
// vocals (instrumental removed). Rendered only while karaoke mode is active.
export function KaraokeStemButton({ active, stem, disabled, onToggle }: KaraokeStemButtonProps) {
  if (!active) return null;
  const isVocals = stem === 'vocals';
  return (
    <button
      type="button"
      onClick={onToggle}
      disabled={disabled}
      className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50"
      aria-label={isVocals ? 'Switch to instrumental only' : 'Switch to vocals only'}
      aria-pressed={isVocals}
      title={
        isVocals ? 'Vocals only — switch to instrumental' : 'Instrumental — switch to vocals only'
      }
    >
      {isVocals ? <Music2 className="h-4 w-4" /> : <MicVocal className="h-4 w-4" />}
    </button>
  );
}
