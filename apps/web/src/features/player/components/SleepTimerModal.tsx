import { X } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { toast } from '@/shared/ui/Toast';
import { useFocusTrap } from '@/shared/hooks/useFocusTrap';

interface SleepTimerModalProps {
  isOpen: boolean;
  onClose: () => void;
  currentMinutes: number | null;
  hasActiveTimer: boolean;
  onSetTimer: (minutes: number) => void;
  onClearTimer: () => void;
}

const TIMER_OPTIONS = [5, 10, 15, 30, 45, 60];

export function SleepTimerModal({
  isOpen,
  onClose,
  currentMinutes,
  hasActiveTimer,
  onSetTimer,
  onClearTimer,
}: SleepTimerModalProps) {
  const dialogRef = useFocusTrap<HTMLDivElement>(isOpen);

  if (!isOpen) return null;

  const handleSetTimer = (mins: number) => {
    onSetTimer(mins);
    onClose();
    toast.add('info', `Sleep timer set for ${mins} minutes`);
  };

  const handleClearTimer = () => {
    onClearTimer();
    onClose();
    toast.add('info', 'Sleep timer cancelled');
  };

  return (
    <div
      role="presentation"
      className="z-modal-backdrop fixed inset-0 flex items-center justify-center bg-black/50"
      onClick={onClose}
      onKeyDown={e => e.key === 'Escape' && onClose()}
    >
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="sleep-timer-title"
        className="bg-bg-secondary w-80 rounded-lg p-6 shadow-lg"
        onClick={e => e.stopPropagation()}
        onKeyDown={e => e.key !== 'Escape' && e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h3 id="sleep-timer-title" className="text-text-primary text-lg font-semibold">
            Sleep Timer
          </h3>
          <button
            onClick={onClose}
            className="text-text-secondary hover:text-text-primary p-1"
            aria-label="Close"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="space-y-2">
          {TIMER_OPTIONS.map(mins => (
            <button
              key={mins}
              onClick={() => handleSetTimer(mins)}
              className={cn(
                'w-full rounded-md p-3 text-left transition-colors',
                currentMinutes === mins
                  ? 'bg-accent text-text-on-accent'
                  : 'bg-bg-tertiary text-text-primary hover:bg-bg-hover'
              )}
            >
              {mins} minutes
            </button>
          ))}

          {hasActiveTimer && (
            <button
              onClick={handleClearTimer}
              className="bg-error/10 text-error hover:bg-error/20 w-full rounded-md p-3 text-left transition-colors"
            >
              Cancel timer
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
