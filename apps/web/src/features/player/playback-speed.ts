const AUDIOBOOK_SPEED_STEPS = [0.75, 1, 1.25, 1.5, 1.75, 2];

export function nextAudiobookSpeed(currentRate: number): number {
  const index = AUDIOBOOK_SPEED_STEPS.findIndex(step => Math.abs(step - currentRate) < 0.01);
  return AUDIOBOOK_SPEED_STEPS[(index + 1) % AUDIOBOOK_SPEED_STEPS.length] ?? 1;
}
