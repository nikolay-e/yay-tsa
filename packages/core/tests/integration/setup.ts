/**
 * Integration Test Setup
 * Loads environment variables and validates configuration
 */

import { loadEnvironmentConfig } from '../../src/config/env.js';

// Load environment configuration
const config = loadEnvironmentConfig();

const serverUrl = config.jellyfinServerUrl || 'http://localhost:8096';

// Require API key for all integration tests
if (!config.jellyfinApiKey) {
  throw new Error(
    'JELLYFIN_API_KEY is required for integration tests. ' +
    'Please create a .env file with your API key.'
  );
}

// Export configuration for tests to use
export const integrationConfig = {
  apiKey: config.jellyfinApiKey,
  username: process.env.JELLYFIN_USERNAME || 'testuser',
  password: process.env.JELLYFIN_PASSWORD || 'testpass123',
  serverUrl,
  clientName: config.jellyfinClientName || 'Jellyfin Mini Test Client',
  deviceName: config.jellyfinDeviceName || 'Integration Test Runner',
  deviceId: config.jellyfinDeviceId || 'test-device-' + Date.now(),
  useApiKey: true,
};

console.log('\n🧪 Integration Test Configuration:');
console.log(`  Server: ${integrationConfig.serverUrl}`);
if (integrationConfig.apiKey) {
  console.log(`  API Key: ${integrationConfig.apiKey.substring(0, 8)}...`);
} else {
  console.log(`  Username: ${integrationConfig.username}`);
}
console.log(`  Client: ${integrationConfig.clientName}`);
console.log(`  Device: ${integrationConfig.deviceName}\n`);
