import { Heart } from 'lucide-react';
import { useFavoriteToggle } from '@/features/library/hooks/useFavorites';
import { cn } from '@/shared/utils/cn';

type FavoriteItemType = 'track' | 'album' | 'artist' | 'item';

type FavoriteButtonProps = Readonly<{
  itemId: string;
  isFavorite: boolean;
  itemType?: FavoriteItemType;
  size?: 'sm' | 'md';
  disabled?: boolean;
  className?: string;
  'data-testid'?: string;
}>;

const NOUN: Record<FavoriteItemType, string> = {
  track: 'track',
  album: 'album',
  artist: 'artist',
  item: 'item',
};

/**
 * The single favorite control for the whole app. Reads its filled/empty state from `isFavorite`
 * (sourced from `UserData.IsFavorite` via getIsFavorite at the call site) and toggles through the
 * shared useFavoriteToggle mutation — never its own POST/DELETE. Optimistic update flips every other
 * instance of the same item instantly; this button disables itself while its own request is pending
 * so the same control can't be double-submitted.
 */
export function FavoriteButton({
  itemId,
  isFavorite,
  itemType = 'item',
  size = 'sm',
  disabled = false,
  className,
  'data-testid': testId,
}: FavoriteButtonProps) {
  const { mutate, isPending } = useFavoriteToggle();

  const iconSize = size === 'sm' ? 'h-4 w-4' : 'h-5 w-5';
  const noun = NOUN[itemType];
  const label = isFavorite ? `Remove ${noun} from favorites` : `Add ${noun} to favorites`;

  return (
    <button
      type="button"
      onClick={e => {
        e.preventDefault();
        e.stopPropagation();
        mutate({ itemId, isFavorite });
      }}
      disabled={disabled || isPending}
      className={cn(
        'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
        isFavorite ? 'text-accent' : 'text-text-secondary hover:text-text-primary',
        isPending && 'opacity-50',
        className
      )}
      aria-pressed={isFavorite}
      aria-label={label}
      title={label}
      data-testid={testId}
    >
      <Heart className={iconSize} fill={isFavorite ? 'currentColor' : 'none'} />
    </button>
  );
}
