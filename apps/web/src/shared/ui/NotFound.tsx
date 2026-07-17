import { Link } from 'react-router-dom';

type NotFoundProps = Readonly<{
  message: string;
}>;

export function NotFound({ message }: NotFoundProps) {
  return (
    <div className="p-6">
      <h1 className="text-text-primary text-2xl font-bold">{message}</h1>
      <Link
        to="/"
        className="text-accent mt-4 inline-flex items-center gap-2 transition-colors hover:underline"
      >
        Back to Home
      </Link>
    </div>
  );
}
