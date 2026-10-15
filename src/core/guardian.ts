import { NativeModules } from 'react-native';
import { getLimits } from './db'; import { upsert, remove, PRIORITY } from './activities';
const N = NativeModules.IslandModule; let angryPkg: string | null = null;
// Polls usage events (~every 5s). Android has no push API for foreground changes without Accessibility, so detection lags up to ~5s.
export function startGuardian(angry = '😡', happy = '😊') {
  return setInterval(async () => {
    if (!(await N.hasUsageAccess())) return;
    const fg: string | null = await N.foregroundPackage();
    if (angryPkg && fg && fg !== angryPkg) {
      remove('screentime');
      upsert({ id: 'happy', priority: PRIORITY.screentime, title: happy, expiresAt: Date.now() + 2500 }); angryPkg = null;
    }
    for (const l of await getLimits()) {
      if (fg === l.pkg && (await N.usageTodayMs(l.pkg)) > l.limitMin * 60000) {
        angryPkg = l.pkg; upsert({ id: 'screentime', priority: PRIORITY.screentime, title: angry, sub: 'Time limit reached' });
      }
    }
  }, 5000);
}
