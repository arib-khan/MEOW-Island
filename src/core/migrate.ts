/**
 * Config migration (pure). Version 2: the island may no longer be dragged around the phone, and the default position is the
 * status-bar band; old installs that saved the previous default ("usable") are moved to it once.
 */
export const CONFIG_VERSION = 2;
export function migrateConfig(saved: any): any {
  const s = saved && typeof saved === 'object' ? { ...saved } : {};
  if ((s.cfgVersion ?? 1) < CONFIG_VERSION) { s.posArea = 'statusbar'; s.cfgVersion = CONFIG_VERSION; }
  delete s.dragEnabled;
  return s;
}
