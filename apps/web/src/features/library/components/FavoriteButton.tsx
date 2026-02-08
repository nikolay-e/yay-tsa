import { Heart } from 'lucide-react';
import { useFavoriteToggle } from '@/features/library/hooks/useFavorites';
import { cn } from '@/shared/utils/cn';

interface FavoriteButtonProps {
  itemId: string;
  isFavorite: boolean;
  size?: 'sm' | 'md';
  className?: string;
}

export function FavoriteButton({
  itemId,
  isFavorite,
  size = 'sm',
  className,
}: FavoriteButtonProps) {
  const { mutate, isPending } = useFavoriteToggle();

  const iconSize = size === 'sm' ? 'h-4 w-4' : 'h-5 w-5';

  return (
    <button
      type="button"
      onClick={e => {
        e.preventDefault();
        e.stopPropagation();
        mutate({ itemId, isFavorite });
      }}
      disabled={isPending}
      className={cn(
        'focus-visible:ring-accent rounded-full p-2 transition-colors focus-visible:ring-2 focus-visible:outline-none',
        isFavorite ? 'text-accent' : 'text-text-secondary hover:text-text-primary',
        isPending && 'opacity-50',
        className
      )}
      aria-pressed={isFavorite}
      aria-label={isFavorite ? 'Remove from favorites' : 'Add to favorites'}
    >
      <Heart className={iconSize} fill={isFavorite ? 'currentColor' : 'none'} />
    </button>
  );
}
