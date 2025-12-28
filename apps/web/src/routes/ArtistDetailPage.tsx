import { useParams } from 'react-router-dom';

export function ArtistDetailPage() {
  const { id } = useParams();

  return (
    <div className="p-lg">
      <h1 className="mb-md text-2xl font-bold">Artist Detail</h1>
      <p className="text-text-secondary">Artist ID: {id}</p>
    </div>
  );
}
