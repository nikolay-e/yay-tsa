import { useRecommendationFeed } from './useRecommendationFeed';

export const DISCOVER_QUERY_KEY = ['recommend', 'discover'] as const;

export const useDiscover = (limit?: number) =>
  useRecommendationFeed(DISCOVER_QUERY_KEY, async (service, n) => service.getDiscover(n), limit);
