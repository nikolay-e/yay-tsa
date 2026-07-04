import { useEffect, useRef, useState } from 'react';
import { Search, X } from 'lucide-react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { cn } from '@/shared/utils/cn';

type GlobalSearchBarProps = Readonly<{
  /** Mobile-only: when true the bar slides up out of view (revealed again on scroll-up / pull-down).
   *  Ignored at `md:` and up, where the bar is always visible. */
  hidden?: boolean;
}>;

export function GlobalSearchBar({ hidden = false }: GlobalSearchBarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const onSearchRoute = location.pathname === '/search';
  const [value, setValue] = useState(() => (onSearchRoute ? (searchParams.get('q') ?? '') : ''));
  const [expandedMobile, setExpandedMobile] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (onSearchRoute) setValue(searchParams.get('q') ?? '');
  }, [onSearchRoute, searchParams]);

  const submit = (next: string, options?: { replace?: boolean }) => {
    setValue(next);
    const trimmed = next.trim();
    navigate(trimmed ? `/search?q=${encodeURIComponent(trimmed)}` : '/search', {
      replace: options?.replace,
    });
  };

  const openMobile = () => {
    setExpandedMobile(true);
    requestAnimationFrame(() => inputRef.current?.focus());
  };

  return (
    <div
      data-testid="global-search-bar"
      className={cn(
        'px-safe pt-safe sticky top-0 z-30',
        // A hairline bottom border + blur reads as a deliberate top toolbar rather than a floating
        // strip. Hide-on-scroll is mobile-only; md:translate-y-0 pins it back on desktop. Stays
        // visible while the mobile field is expanded so an active search never disappears mid-typing.
        'border-border/60 bg-bg-primary/85 border-b backdrop-blur-md',
        'transition-transform duration-200 ease-out md:translate-y-0',
        hidden && !expandedMobile ? '-translate-y-full' : 'translate-y-0'
      )}
    >
      <div className="flex h-14 items-center gap-2 px-4 md:px-6">
        {/* Desktop: always-visible field. Mobile: magnifier that expands to a field. */}
        <button
          type="button"
          onClick={openMobile}
          aria-label="Search"
          className={cn(
            'text-text-secondary hover:text-text-primary focus-visible:ring-accent flex min-h-11 min-w-11 items-center justify-center rounded-full transition-colors focus-visible:ring-2 focus-visible:outline-none',
            'md:hidden',
            expandedMobile && 'hidden'
          )}
        >
          <Search className="h-5 w-5" />
        </button>

        <form
          onSubmit={e => {
            e.preventDefault();
            submit(value);
          }}
          className={cn(
            'relative w-full md:max-w-lg',
            expandedMobile ? 'block' : 'hidden md:block'
          )}
        >
          <Search className="text-text-tertiary pointer-events-none absolute top-1/2 left-3.5 h-4 w-4 -translate-y-1/2" />
          <input
            ref={inputRef}
            type="search"
            value={value}
            onChange={e => submit(e.target.value, { replace: onSearchRoute })}
            placeholder="Search music..."
            aria-label="Search music"
            data-testid="global-search-input"
            maxLength={200}
            className="border-border/70 bg-bg-secondary/60 text-text-primary placeholder:text-text-tertiary hover:border-border focus:border-accent focus-visible:ring-accent/50 h-10 w-full rounded-full border py-2 pr-10 pl-10 text-sm transition-colors focus-visible:ring-2 focus-visible:outline-none"
          />
          {expandedMobile && (
            <button
              type="button"
              onClick={() => {
                setExpandedMobile(false);
                setValue('');
              }}
              aria-label="Close search"
              className="text-text-secondary hover:text-text-primary absolute top-1/2 right-2 flex h-7 w-7 -translate-y-1/2 items-center justify-center rounded-full md:hidden"
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </form>
      </div>
    </div>
  );
}
