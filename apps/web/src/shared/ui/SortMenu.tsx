import React, { useState, useRef, useEffect, useCallback } from 'react';
import { ArrowUpDown, Check } from 'lucide-react';
import { cn } from '@/shared/utils/cn';

export interface SortOption {
  id: string;
  label: string;
  sortBy: string;
  sortOrder: 'Ascending' | 'Descending';
}

export const LIBRARY_SORT_OPTIONS: SortOption[] = [
  {
    id: 'recently-played',
    label: 'Recently Played',
    sortBy: 'DatePlayed',
    sortOrder: 'Descending',
  },
  { id: 'name-asc', label: 'Name (A → Z)', sortBy: 'SortName', sortOrder: 'Ascending' },
  { id: 'name-desc', label: 'Name (Z → A)', sortBy: 'SortName', sortOrder: 'Descending' },
  { id: 'recently-added', label: 'Recently Added', sortBy: 'DateCreated', sortOrder: 'Descending' },
];

export const FAVORITES_SORT_OPTIONS: SortOption[] = [
  {
    id: 'custom-order',
    label: 'Custom Order',
    sortBy: 'FavoritePosition',
    sortOrder: 'Ascending',
  },
  {
    id: 'date-liked',
    label: 'Date Liked',
    sortBy: 'DateFavorited',
    sortOrder: 'Descending',
  },
  ...LIBRARY_SORT_OPTIONS,
];

const STORAGE_KEY = 'yaytsa_sort_prefs';

type SortTab = 'songs' | 'albums' | 'artists';
type SortContext = 'library' | 'favorites';

function storageKey(tab: SortTab, context: SortContext): string {
  return `${context}:${tab}`;
}

function loadSortPreference(tab: SortTab, context: SortContext, defaultId: string): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      const prefs = JSON.parse(stored) as Record<string, string>;
      const key = storageKey(tab, context);
      if (prefs[key]) return prefs[key];
      if (context === 'library' && prefs[tab]) return prefs[tab];
    }
  } catch {
    // ignore
  }
  return defaultId;
}

function saveSortPreference(tab: SortTab, context: SortContext, optionId: string) {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    const prefs = stored ? (JSON.parse(stored) as Record<string, string>) : {};
    prefs[storageKey(tab, context)] = optionId;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // ignore
  }
}

export function useSortPreference(
  tab: SortTab,
  options: SortOption[] = LIBRARY_SORT_OPTIONS,
  context: SortContext = 'library'
): {
  selectedId: string;
  activeOption: SortOption;
  select: (optionId: string) => void;
} {
  const defaultId = options[0]?.id ?? 'recently-played';
  const [selectedId, setSelectedId] = useState(() => loadSortPreference(tab, context, defaultId));

  const matched = options.find(o => o.id === selectedId);
  const activeOption: SortOption = matched ?? options[0]!;

  const select = useCallback(
    (optionId: string) => {
      setSelectedId(optionId);
      saveSortPreference(tab, context, optionId);
    },
    [tab, context]
  );

  return { selectedId, activeOption, select };
}

type SortMenuProps = Readonly<{
  selectedId: string;
  onSelect: (optionId: string) => void;
  options?: SortOption[];
  className?: string;
}>;

export function SortMenu({
  selectedId,
  onSelect,
  options = LIBRARY_SORT_OPTIONS,
  className,
}: SortMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const menuRef = useRef<HTMLDivElement>(null);
  const listboxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    function handleClickOutside(e: PointerEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('pointerdown', handleClickOutside);
    return () => document.removeEventListener('pointerdown', handleClickOutside);
  }, [isOpen]);

  useEffect(() => {
    if (isOpen) {
      const currentIndex = options.findIndex(o => o.id === selectedId);
      setActiveIndex(Math.max(currentIndex, 0));
      requestAnimationFrame(() => listboxRef.current?.focus());
    }
  }, [isOpen, options, selectedId]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      switch (e.key) {
        case 'Escape':
          e.preventDefault();
          setIsOpen(false);
          break;
        case 'ArrowDown':
          e.preventDefault();
          setActiveIndex(prev => (prev + 1) % options.length);
          break;
        case 'ArrowUp':
          e.preventDefault();
          setActiveIndex(prev => (prev <= 0 ? options.length - 1 : prev - 1));
          break;
        case 'Enter':
        case ' ':
          e.preventDefault();
          if (activeIndex >= 0 && activeIndex < options.length) {
            onSelect(options[activeIndex]!.id);
            setIsOpen(false);
          }
          break;
      }
    },
    [activeIndex, options, onSelect]
  );

  const activeOption = options.find(o => o.id === selectedId) ?? options[0]!;

  return (
    <div ref={menuRef} className={cn('relative', className)}>
      <button
        type="button"
        onClick={() => setIsOpen(prev => !prev)}
        aria-haspopup="listbox"
        aria-expanded={isOpen}
        className={cn(
          'flex items-center gap-1.5 rounded-sm px-3 py-2 text-sm',
          'border-border bg-bg-secondary border',
          'text-text-secondary hover:text-text-primary',
          'focus-visible:ring-accent transition-colors focus-visible:ring-2 focus-visible:outline-none'
        )}
      >
        <ArrowUpDown className="h-4 w-4" />
        <span className="hidden sm:inline">{activeOption.label}</span>
      </button>

      {isOpen && (
        <div
          ref={listboxRef}
          role="listbox"
          tabIndex={0}
          aria-activedescendant={
            activeIndex >= 0 && activeIndex < options.length
              ? `sort-option-${options[activeIndex]!.id}`
              : undefined
          }
          onKeyDown={handleKeyDown}
          className={cn(
            'z-dropdown absolute top-full right-0 mt-1 min-w-48',
            'border-border bg-bg-secondary rounded-md border shadow-lg',
            'focus:outline-none'
          )}
        >
          {options.map((option, index) => (
            <button
              key={option.id}
              id={`sort-option-${option.id}`}
              type="button"
              role="option"
              aria-selected={option.id === selectedId}
              onClick={() => {
                onSelect(option.id);
                setIsOpen(false);
              }}
              className={cn(
                'flex w-full items-center justify-between gap-3 px-3 py-2.5 text-sm',
                'transition-colors first:rounded-t-md last:rounded-b-md',
                index === activeIndex && 'bg-bg-tertiary',
                option.id === selectedId
                  ? 'text-accent bg-accent/10'
                  : 'text-text-secondary hover:bg-bg-tertiary hover:text-text-primary'
              )}
            >
              <span>{option.label}</span>
              {option.id === selectedId && <Check className="h-4 w-4 shrink-0" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
