import { useEffect, useRef, useState } from 'react';
import { Search, X } from 'lucide-react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { cn } from '@/shared/utils/cn';

export function GlobalSearchBar() {
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
    <div className="bg-bg-primary/80 px-safe pt-safe sticky top-0 z-30 backdrop-blur-sm">
      <div className="flex items-center gap-2 px-4 py-2 md:px-6">
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
            'relative w-full md:block md:max-w-md',
            expandedMobile ? 'block' : 'hidden md:block'
          )}
        >
          <Search className="text-text-tertiary pointer-events-none absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2" />
          <input
            ref={inputRef}
            type="search"
            value={value}
            onChange={e => submit(e.target.value, { replace: true })}
            placeholder="Search music..."
            aria-label="Search music"
            data-testid="global-search-input"
            maxLength={200}
            className="border-border bg-bg-secondary text-text-primary placeholder:text-text-tertiary focus:border-accent focus-visible:ring-accent w-full rounded-lg border py-2 pr-9 pl-9 transition-colors focus-visible:ring-2 focus-visible:outline-none"
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
