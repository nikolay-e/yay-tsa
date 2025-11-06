/**
 * Time formatting utilities
 */

/**
 * Format seconds to MM:SS or HH:MM:SS format
 */
export function formatDuration(seconds: number): string {
  // Handle invalid or negative values
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '0:00';
  }

  // Handle zero explicitly for clarity
  if (seconds === 0) {
    return '0:00';
  }

  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);

  if (hours > 0) {
    return `${hours}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  }

  return `${minutes}:${secs.toString().padStart(2, '0')}`;
}

/**
 * Format time (alias for formatDuration for backward compatibility)
 */
export function formatTime(seconds: number): string {
  return formatDuration(seconds);
}
