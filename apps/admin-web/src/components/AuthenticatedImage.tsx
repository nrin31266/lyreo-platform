import { useEffect, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { http } from '@/lib/api';

type AuthenticatedImageProps = {
  src: string | null | undefined;
  alt: string;
  className?: string;
  fallback?: ReactNode;
};

export function AuthenticatedImage({
  src,
  alt,
  className,
  fallback = null,
}: AuthenticatedImageProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ['auth-image', src],
    enabled: Boolean(src),
    staleTime: 5 * 60_000,
    retry: false,
    queryFn: async ({ signal }) => {
      const response = await http.get<Blob>(src!, { responseType: 'blob', signal });
      return response.data;
    },
  });

  useEffect(() => {
    if (!query.data || typeof URL.createObjectURL !== 'function') {
      setObjectUrl(null);
      return;
    }
    const nextUrl = URL.createObjectURL(query.data);
    setObjectUrl(nextUrl);
    return () => {
      URL.revokeObjectURL(nextUrl);
    };
  }, [query.data]);

  if (!src || !objectUrl) {
    return <>{fallback}</>;
  }

  return <img src={objectUrl} alt={alt} className={className} />;
}
