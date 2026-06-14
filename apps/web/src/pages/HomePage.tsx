import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { DailyMix, ExploreNew, FavoriteSongs } from '@/features/library/components';
import { RadioSeeds } from '@/features/radio';
import { SearchInput } from '@/shared/ui/SearchInput';

function HomeSearch() {
  const navigate = useNavigate();
  const [value, setValue] = useState('');

  const handleChange = (next: string) => {
    setValue(next);
    const trimmed = next.trim();
    if (trimmed) navigate(`/search?q=${encodeURIComponent(trimmed)}`);
  };

  return (
    <div className="mb-4">
      <SearchInput
        value={value}
        onChange={handleChange}
        placeholder="Search everything..."
        className="sm:w-full"
      />
    </div>
  );
}

export function HomePage() {
  return (
    <div className="p-4">
      <h1 className="sr-only">Home</h1>
      <HomeSearch />
      <RadioSeeds />
      <DailyMix />
      <ExploreNew />
      <FavoriteSongs />
    </div>
  );
}
