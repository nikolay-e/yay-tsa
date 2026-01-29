import { useRef, type ChangeEvent } from 'react';
import { Volume2, VolumeX } from 'lucide-react';

interface VolumeControlsProps {
  volume: number;
  onVolumeChange: (volume: number) => void;
}

const DEFAULT_UNMUTE_VOLUME = 0.5;

export function VolumeControls({ volume, onVolumeChange }: VolumeControlsProps) {
  const previousVolumeRef = useRef(1);

  const toggleMute = () => {
    if (volume > 0) {
      previousVolumeRef.current = volume;
      onVolumeChange(0);
    } else {
      const restoreVolume =
        previousVolumeRef.current > 0 ? previousVolumeRef.current : DEFAULT_UNMUTE_VOLUME;
      onVolumeChange(restoreVolume);
    }
  };

  const handleVolumeChange = (e: ChangeEvent<HTMLInputElement>) => {
    onVolumeChange(parseFloat(e.target.value));
  };

  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        onClick={toggleMute}
        className="text-text-secondary hover:text-text-primary focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none"
        aria-label={volume > 0 ? 'Mute' : 'Unmute'}
        aria-pressed={volume === 0}
      >
        {volume > 0 ? <Volume2 className="h-4 w-4" /> : <VolumeX className="h-4 w-4" />}
      </button>

      <input
        data-testid="volume-slider"
        type="range"
        min="0"
        max="1"
        step="0.01"
        value={volume}
        onChange={handleVolumeChange}
        className="bg-bg-tertiary accent-accent h-1 w-24 cursor-pointer appearance-none rounded-full"
        aria-label="Volume"
      />
    </div>
  );
}
