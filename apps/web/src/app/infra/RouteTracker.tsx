import { useEffect } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { setReporterRoute } from '@/shared/utils/error-reporter';

const ROUTE_TITLES: Record<string, string> = {
  '/': 'Home',
  '/search': 'Search',
  '/favorites': 'Favorites',
  '/artists': 'Artists',
  '/albums': 'Albums',
  '/playlists': 'Playlists',
  '/audiobooks': 'Audiobooks',
  '/offline': 'Downloads',
  '/settings': 'Settings',
};

export function RouteTracker() {
  const location = useLocation();
  const params = useParams();

  useEffect(() => {
    const routeTemplate = toRouteTemplate(location.pathname, params);
    setReporterRoute(routeTemplate);
    const pageName = ROUTE_TITLES[routeTemplate];
    document.title = pageName ? `${pageName} — Yay-Tsa` : 'Yay-Tsa';
  }, [location.pathname, params]);

  return null;
}

function toRouteTemplate(pathname: string, params: Record<string, string | undefined>): string {
  const valueToKey = new Map<string, string>();
  for (const [paramName, paramValue] of Object.entries(params)) {
    if (paramValue && paramValue.length > 0) valueToKey.set(paramValue, paramName);
  }
  return pathname
    .split('/')
    .map(segment => {
      const key = valueToKey.get(segment);
      return key ? `:${key}` : segment;
    })
    .join('/');
}
