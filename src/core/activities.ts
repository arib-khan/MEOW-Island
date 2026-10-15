// Priority arbiter: highest priority wins the island; lower ones keep their state and resurface when higher ones expire.
export type Activity = { id: string; priority: number; title: string; sub?: string; expiresAt?: number };
const acts = new Map<string, Activity>(); let sink: (a: Activity | null) => void = () => {};
export const bindSink = (f: typeof sink) => { sink = f; };
export function upsert(a: Activity) { acts.set(a.id, a); flush(); }
export function remove(id: string) { acts.delete(id); flush(); }
function flush() {
  const now = Date.now();
  for (const [k, v] of acts) if (v.expiresAt && v.expiresAt < now) acts.delete(k);
  sink([...acts.values()].sort((a, b) => b.priority - a.priority)[0] ?? null);
}
setInterval(flush, 1000); // display refresh only; timer truth is stored timestamps
export const PRIORITY = { reminder: 90, screentime: 80, timer: 70, notification: 60, music: 50, battery: 40, weather: 30 };
