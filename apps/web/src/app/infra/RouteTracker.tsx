import { useEffect } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { setReporterRoute } from '@/shared/utils/error-reporter';

export function RouteTracker() {
  const location = useLocation();
  const params = useParams();

  useEffect(() => {
    setReporterRoute(toRouteTemplate(location.pathname, params));
  }, [location.pathname, params]);

  return null;
}

function toRouteTemplate(pathname: string, params: Record<string, string | undefined>): string {
  const valueToKey = new Map<string, string>();
  for (const [key, value] of Object.entries(params)) {
    if (value && value.length > 0) valueToKey.set(value, key);
  }
  return pathname
    .split('/')
    .map(segment => {
      const key = valueToKey.get(segment);
      return key ? `:${key}` : segment;
    })
    .join('/');
}
