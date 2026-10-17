/** Re-mounts on every navigation, so the CSS fade below plays as a light page transition. */
export default function Template({ children }: { children: React.ReactNode }) {
  return <div className="page-fade">{children}</div>;
}
