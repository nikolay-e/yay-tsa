export const TEST_CREDENTIALS = {
  USERNAME: process.env.YAYTSA_TEST_USERNAME || 'test-user',
  PASSWORD: process.env.YAYTSA_TEST_PASSWORD || 'test-password',
} as const;

export const TEST_DATA = {
  SEARCH_NO_RESULTS_QUERY: 'xyznonexistentquery999',
  SEARCH_NO_RESULTS_ALBUM: 'xyznonexistentalbumname12345',
  SEARCH_PARTIAL_LENGTH: 5,
  SEARCH_SHORT_LENGTH: 3,
} as const;
