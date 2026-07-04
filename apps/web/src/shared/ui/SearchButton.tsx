import { Search } from 'lucide-react';
import { useSearchPanelStore } from '@/shared/stores/search-panel.store';
import { cn } from '@/shared/utils/cn';

type SearchButtonProps = Readonly<{ className?: string }>;

/**
 * Magnifier button that opens the global search panel. Styled to sit next to SortMenu in a page
 * header (same height / border treatment), so a page's toolbar reads as one control group.
 */
export function SearchButton({ className }: SearchButtonProps) {
  const open = useSearchPanelStore(state => state.open);
  return (
    <button
      type="button"
      onClick={open}
      aria-label="Search"
      data-testid="open-search"
      className={cn(
        'flex items-center justify-center rounded-sm px-3 py-2',
        'border-border bg-bg-secondary border',
        'text-text-secondary hover:text-text-primary',
        'focus-visible:ring-accent transition-colors focus-visible:ring-2 focus-visible:outline-none',
        className
      )}
    >
      <Search className="h-4 w-4" />
    </button>
  );
}
