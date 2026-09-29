/** A label's on-screen box in pixels (top-left origin) and how much it matters. */
export type LabelBox = {
  key: string;
  priority: number;
  x: number;
  y: number;
  width: number;
  height: number;
};

/**
 * Greedy declutter: keep labels from highest priority down, skipping any that would overlap
 * (with `padding` px of breathing room) a label already kept.
 */
export function pickVisibleLabels(boxes: LabelBox[], padding = 2): Set<string> {
  const kept: LabelBox[] = [];
  for (const box of [...boxes].sort((a, b) => b.priority - a.priority)) {
    const overlaps = kept.some(
      (other) =>
        box.x - padding < other.x + other.width &&
        other.x - padding < box.x + box.width &&
        box.y - padding < other.y + other.height &&
        other.y - padding < box.y + box.height,
    );
    if (!overlaps) {
      kept.push(box);
    }
  }
  return new Set(kept.map((box) => box.key));
}
