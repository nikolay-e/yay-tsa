export function redactSecrets(input: string): string {
  return input
    .replaceAll(/api_key=[^&\s]+/gi, 'api_key=[REDACTED]')
    .replaceAll(/token=[^&\s]+/gi, 'token=[REDACTED]')
    .replaceAll(/password=[^&\s]+/gi, 'password=[REDACTED]');
}
