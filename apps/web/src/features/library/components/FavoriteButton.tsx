import { Heart } from 'lucide-react';
import {
  useFavoriteToggle,
  useIsFavoritePending,
  useFavoritePendingStore,
} from '@/features/library/hooks/useFavorites';
import { cn } from '@/shared/utils/cn';

// Favorites are per-track by design (album/artist "favorites" are DERIVED from favorited
// tracks server-side; the backend 404s any non-track id) — so only track-level hearts exist.
type FavoriteItemType = 'track' | 'item';

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
  item: 'item',
};

/**
 * The single favorite control for the whole app. Reads its filled/empty state from `isFavorite`
 * (sourced from `UserData.IsFavorite` via getIsFavorite at the call site) and toggles through the
 * shared useFavoriteToggle mutation — never its own POST/DELETE. Optimistic update flips every other
 * instance of the same item instantly. Pending is locked per item id (shared across all instances),
 * acquired synchronously on click so a concurrent toggle from any other location is rejected before
 * a second request can start.
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
  const { mutate } = useFavoriteToggle();
  const isPending = useIsFavoritePending(itemId);

  const iconSize = size === 'sm' ? 'h-4 w-4' : 'h-5 w-5';
  const noun = NOUN[itemType];
  const label = isFavorite ? `Remove ${noun} from favorites` : `Add ${noun} to favorites`;

  return (
    <button
      type="button"
      onClick={e => {
        e.preventDefault();
        e.stopPropagation();
        // Acquire the per-item lock synchronously at click time. getState() reflects begin()
        // immediately, so a second click (here or on any other instance of this item) in the same
        // frame is rejected before a duplicate request starts.
        const store = useFavoritePendingStore.getState();
        if (store.pending.has(itemId)) return;
        store.begin(itemId);
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
