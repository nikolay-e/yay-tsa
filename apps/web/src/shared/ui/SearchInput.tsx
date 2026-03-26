import { Search } from 'lucide-react';
import { cn } from '@/shared/utils/cn';

type SearchInputProps = Readonly<{
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  maxLength?: number;
  className?: string;
}>;

export function SearchInput({
  value,
  onChange,
  placeholder,
  maxLength = 200,
  className,
}: SearchInputProps) {
  return (
    <div className={cn('relative w-full sm:w-96', className)}>
      <Search className="text-text-tertiary absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2" />
      <input
        type="text"
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        maxLength={maxLength}
        data-testid="search-input"
        className={cn(
          'border-border bg-bg-secondary w-full rounded-lg border py-2.5 pr-4 pl-9 sm:rounded-md sm:py-2',
          'text-text-primary placeholder:text-text-tertiary',
          'focus:border-accent focus-visible:ring-accent transition-colors focus-visible:ring-2 focus-visible:outline-none'
        )}
      />
    </div>
  );
}
