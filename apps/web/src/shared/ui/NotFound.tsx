interface NotFoundProps {
  message: string;
}

export function NotFound({ message }: NotFoundProps) {
  return (
    <div className="p-6">
      <p className="text-text-secondary">{message}</p>
    </div>
  );
}
