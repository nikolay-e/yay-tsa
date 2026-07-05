import { useEffect, useRef, useState } from 'react';
import { Search, X } from 'lucide-react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useSearchPanelStore } from '@/shared/stores/search-panel.store';
import { cn } from '@/shared/utils/cn';

/**
 * Global search panel. Opened by the magnifier button in a page header (slides down from the top as
 * a fixed overlay with a backdrop), and pinned open in-flow on the `/search` route so its results
 * page always has its input. One input drives everything via the URL `?q=`.
 */
export function GlobalSearchBar() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const onSearchRoute = location.pathname === '/search';
  const storeOpen = useSearchPanelStore(state => state.isOpen);
  const close = useSearchPanelStore(state => state.close);

  // On /search the panel is pinned in the content flow (pushes the results down and sticks on
  // scroll). Elsewhere it is a fixed top overlay toggled by the header button.
  const pinned = onSearchRoute;
  const open = pinned || storeOpen;

  const [value, setValue] = useState(() => (onSearchRoute ? (searchParams.get('q') ?? '') : ''));
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (onSearchRoute) setValue(searchParams.get('q') ?? '');
  }, [onSearchRoute, searchParams]);

  // Focus the field whenever the panel opens.
  useEffect(() => {
    if (open) requestAnimationFrame(() => inputRef.current?.focus());
  }, [open]);

  const submit = (next: string, options?: { replace?: boolean }) => {
    setValue(next);
    const trimmed = next.trim();
    navigate(trimmed ? `/search?q=${encodeURIComponent(trimmed)}` : '/search', {
      replace: options?.replace,
    });
  };

  const handleClose = () => {
    setValue('');
    close();
    // Leaving /search collapses the pinned panel (its visibility is derived from the route).
    if (onSearchRoute) navigate('/');
  };

  // Escape closes the panel from anywhere while it is open. Capture phase so this runs before the
  // native <input type="search"> Escape-to-clear handler, which otherwise swallows the key.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleClose();
    };
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
    // handleClose reads only refs/stable setters; re-binding each render is unnecessary.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, onSearchRoute]);

  return (
    <>
      {/* Dim + click-to-dismiss when opened as an overlay (never on the pinned /search route). */}
      {storeOpen && !pinned && (
        <div
          className="z-modal-backdrop md:left-sidebar fixed inset-0 bg-black/40"
          onClick={handleClose}
          aria-hidden="true"
        />
      )}
      <div
        data-testid="global-search-bar"
        className={cn(
          'px-safe pt-safe border-border/60 bg-bg-primary/95 border-b backdrop-blur-md',
          pinned
            ? 'sticky top-0 z-30'
            : cn(
                'z-modal md:left-sidebar fixed top-0 right-0 left-0',
                'transition-transform duration-200 ease-out',
                open ? 'translate-y-0' : 'pointer-events-none -translate-y-full'
              )
        )}
      >
        <div className="flex h-14 items-center gap-3 px-4 md:px-6">
          <Search className="text-text-tertiary pointer-events-none h-5 w-5 shrink-0" />
          <form
            onSubmit={e => {
              e.preventDefault();
              submit(value);
            }}
            className="min-w-0 flex-1"
          >
            <input
              ref={inputRef}
              type="search"
              value={value}
              onChange={e => submit(e.target.value, { replace: onSearchRoute })}
              placeholder="Search music..."
              aria-label="Search music"
              data-testid="global-search-input"
              maxLength={200}
              tabIndex={open ? 0 : -1}
              className="text-text-primary placeholder:text-text-tertiary w-full bg-transparent text-base outline-none"
            />
          </form>
          <button
            type="button"
            onClick={handleClose}
            aria-label="Close search"
            tabIndex={open ? 0 : -1}
            className="text-text-secondary hover:text-text-primary focus-visible:ring-accent flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-colors focus-visible:ring-2 focus-visible:outline-none"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
      </div>
    </>
  );
}
