import { useRecommendationFeed } from './useRecommendationFeed';

export const DAILY_MIX_QUERY_KEY = ['recommend', 'daily-mix'] as const;

export const useDailyMix = (limit?: number) =>
  useRecommendationFeed(DAILY_MIX_QUERY_KEY, async (service, n) => service.getDailyMix(n), limit);
