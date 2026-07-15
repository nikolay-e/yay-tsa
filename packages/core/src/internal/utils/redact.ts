export function redactSecrets(input: string): string {
  let out = input
    .replaceAll(/api_key=[^&\s"')<>]+/gi, 'api_key=[REDACTED]')
    .replaceAll(/access_token=[^&\s"')<>]+/gi, 'access_token=[REDACTED]')
    .replaceAll(/token=[^&\s"')<>]+/gi, 'token=[REDACTED]')
    .replaceAll(/password=[^&\s"')<>]+/gi, 'password=[REDACTED]')
    .replaceAll(/Bearer\s+[^\s"')<>]+/gi, 'Bearer [REDACTED]')
    .replaceAll(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[REDACTED_JWT]')
    .replaceAll(/(cookie|csrf[-_]?token|x-csrf-token)\s*[:=]\s*[^\s;"']+/gi, '$1=[REDACTED]')
    .replaceAll(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g, '[REDACTED_EMAIL]');
  out = out.replaceAll(/(https?:\/\/[^\s"'<>]+?)[?#][^\s"'<>]*/gi, '$1');
  return out;
}
