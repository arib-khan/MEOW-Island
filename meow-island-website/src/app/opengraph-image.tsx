import { ImageResponse } from "next/og";

export const alt = "MEOW Island: your phone's cutest companion";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function OgImage() {
  return new ImageResponse(
    (
      <div style={{ width: "100%", height: "100%", display: "flex", flexDirection: "column", justifyContent: "center", padding: 80, color: "white", background: "radial-gradient(900px 500px at 85% 10%, #4c2a9a 0%, #0b0813 65%)" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 18, marginBottom: 36 }}>
          <div style={{ width: 150, height: 54, borderRadius: 27, background: "#000", border: "3px solid #b79cff", display: "flex", alignItems: "center", justifyContent: "center", gap: 22 }}>
            <div style={{ width: 16, height: 22, borderRadius: 10, background: "#b79cff" }} />
            <div style={{ width: 16, height: 22, borderRadius: 10, background: "#b79cff" }} />
          </div>
        </div>
        <div style={{ fontSize: 88, fontWeight: 800, lineHeight: 1.05 }}>Your phone&apos;s cutest companion.</div>
        <div style={{ fontSize: 34, color: "#b79cff", marginTop: 28 }}>MEOW Island · open-source floating island for Android</div>
      </div>
    ),
    size,
  );
}
