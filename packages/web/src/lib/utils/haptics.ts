import { createLogger } from '@yaytsa/core';

const log = createLogger('Haptics');

export type HapticStyle = 'light' | 'medium' | 'heavy' | 'selection';

/**
 * Trigger haptic feedback on supported devices
 * @param style - The intensity/type of haptic feedback
 */
export function triggerHaptic(style: HapticStyle = 'medium'): void {
  if (!('vibrate' in navigator)) {
    return;
  }

  const patterns: Record<HapticStyle, number | number[]> = {
    light: 10,
    medium: 20,
    heavy: 30,
    selection: [5, 10],
  };

  try {
    navigator.vibrate(patterns[style]);
  } catch (error) {
    log.warn('Haptic feedback not supported', { error });
  }
}

/**
 * Trigger haptic feedback for play/pause actions
 */
export function hapticPlayPause(): void {
  triggerHaptic('medium');
}

/**
 * Trigger haptic feedback for navigation/skip actions
 */
export function hapticSkip(): void {
  triggerHaptic('light');
}

/**
 * Trigger haptic feedback for selection/tap actions
 */
export function hapticSelect(): void {
  triggerHaptic('selection');
}
