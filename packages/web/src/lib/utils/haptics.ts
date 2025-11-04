/**
 * Haptic feedback utility for iOS Safari and supporting browsers
 * Provides tactile feedback for user interactions on mobile devices
 */

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
    console.warn('Haptic feedback not supported:', error);
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

/**
 * Trigger haptic feedback for error/warning actions
 */
export function hapticError(): void {
  triggerHaptic('heavy');
}

/**
 * Trigger haptic feedback for successful actions
 */
export function hapticSuccess(): void {
  triggerHaptic('light');
}
