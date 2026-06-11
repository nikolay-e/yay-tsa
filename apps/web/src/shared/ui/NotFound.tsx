type NotFoundProps = Readonly<{
  message: string;
}>;

export function NotFound({ message }: NotFoundProps) {
  return (
    <div className="p-6">
      <h1 className="text-text-primary text-2xl font-bold">{message}</h1>
    </div>
  );
}
