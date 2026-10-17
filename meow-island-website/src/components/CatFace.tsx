import type { CSSProperties } from "react";

export type Mood = "normal" | "angry" | "happy";

/** The mascot, drawn in SVG so it stays crisp and weightless. Same geometry as the app icon. */
export default function CatFace({
  mood = "normal",
  className,
  style,
  title,
}: {
  mood?: Mood;
  className?: string;
  style?: CSSProperties;
  title?: string;
}) {
  const face = mood === "angry" ? "#d99bb8" : "#b79cff";
  const ink = "#140f24";
  return (
    <svg viewBox="0 0 64 64" className={className} style={style} role={title ? "img" : undefined} aria-label={title} aria-hidden={title ? undefined : true}>
      <polygon fill={face} points="12,8 28,19 13,34" />
      <polygon fill={face} points="52,8 36,19 51,34" />
      <polygon fill="#ffa0c8" points="14.5,14 22,19 15,26" />
      <polygon fill="#ffa0c8" points="49.5,14 42,19 49,26" />
      <ellipse cx="32" cy="38" rx="21" ry="17" fill={face} />
      {mood === "angry" && (
        <>
          <path d="M18 29l11 4M46 29l-11 4" stroke={ink} strokeWidth="2.4" strokeLinecap="round" />
          <circle cx="15" cy="30" r="0" />
        </>
      )}
      {mood === "happy" ? (
        <>
          <path d="M20.5 40c1.2-3.6 6.8-3.6 8 0M35.5 40c1.2-3.6 6.8-3.6 8 0" fill="none" stroke={ink} strokeWidth="2.2" strokeLinecap="round" />
          <ellipse cx="17" cy="44" rx="3" ry="1.8" fill="#ffa0c8" opacity=".7" />
          <ellipse cx="47" cy="44" rx="3" ry="1.8" fill="#ffa0c8" opacity=".7" />
        </>
      ) : (
        <g className={mood === "normal" ? "cat-blink" : undefined}>
          <ellipse cx="24.5" cy={mood === "angry" ? 40 : 39} rx="3.2" ry={mood === "angry" ? 3.4 : 4.2} fill={ink} />
          <ellipse cx="39.5" cy={mood === "angry" ? 40 : 39} rx="3.2" ry={mood === "angry" ? 3.4 : 4.2} fill={ink} />
          <circle cx="25.4" cy="37.8" r="1" fill="#fff" />
          <circle cx="40.4" cy="37.8" r="1" fill="#fff" />
        </g>
      )}
      <polygon fill="#ffa0c8" points="29.5,44.5 34.5,44.5 32,47.2" />
      {mood === "angry" ? (
        <path d="M27 52c1.6-2.4 8.4-2.4 10 0" fill="none" stroke={ink} strokeWidth="1.4" strokeLinecap="round" />
      ) : mood === "happy" ? (
        <path d="M27 49.5c1.2 3.6 8.8 3.6 10 0z" fill={ink} />
      ) : (
        <path d="M32 47.2v2M29.6 50.4c-1.1 1.1-2.5 1.1-3.2 0M34.4 50.4c1.1 1.1 2.5 1.1 3.2 0" fill="none" stroke={ink} strokeWidth="1" strokeLinecap="round" />
      )}
    </svg>
  );
}

/** Just the glowing eyes, used where the cat peeks over a card. */
export function PeekingCat({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 160 56" className={className} aria-hidden="true">
      <defs>
        <radialGradient id="peek-eye" cx="50%" cy="50%" r="50%">
          <stop offset="0" stopColor="#f2e9ff" />
          <stop offset=".45" stopColor="#b79cff" />
          <stop offset="1" stopColor="#8b5cf6" />
        </radialGradient>
        <filter id="peek-glow" x="-60%" y="-60%" width="220%" height="220%"><feGaussianBlur stdDeviation="4" /></filter>
      </defs>
      <polygon points="14,56 22,6 56,36 56,56" fill="#1a1430" stroke="#b79cff" strokeOpacity=".35" />
      <polygon points="146,56 138,6 104,36 104,56" fill="#1a1430" stroke="#b79cff" strokeOpacity=".35" />
      <polygon points="24,50 27,20 46,38 46,50" fill="#ffa0c8" opacity=".16" />
      <polygon points="136,50 133,20 114,38 114,50" fill="#ffa0c8" opacity=".16" />
      <rect x="10" y="30" width="140" height="30" rx="16" fill="#1a1430" stroke="#b79cff" strokeOpacity=".3" />
      <g className="eye-glow">
        <ellipse cx="58" cy="44" rx="9" ry="7" fill="#8b5cf6" filter="url(#peek-glow)" />
        <ellipse cx="102" cy="44" rx="9" ry="7" fill="#8b5cf6" filter="url(#peek-glow)" />
      </g>
      <g className="cat-blink">
        <ellipse cx="58" cy="44" rx="7" ry="8" fill="url(#peek-eye)" />
        <ellipse cx="102" cy="44" rx="7" ry="8" fill="url(#peek-eye)" />
        <ellipse cx="58" cy="44" rx="2.2" ry="5.6" fill="#140f24" />
        <ellipse cx="102" cy="44" rx="2.2" ry="5.6" fill="#140f24" />
      </g>
    </svg>
  );
}

export function PawPrint({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden="true" fill="currentColor">
      <ellipse cx="16" cy="21" rx="7" ry="6" />
      <ellipse cx="6.5" cy="14" rx="2.8" ry="3.6" transform="rotate(-18 6.5 14)" />
      <ellipse cx="12.5" cy="8.5" rx="2.8" ry="3.8" transform="rotate(-6 12.5 8.5)" />
      <ellipse cx="19.5" cy="8.5" rx="2.8" ry="3.8" transform="rotate(6 19.5 8.5)" />
      <ellipse cx="25.5" cy="14" rx="2.8" ry="3.6" transform="rotate(18 25.5 14)" />
    </svg>
  );
}
