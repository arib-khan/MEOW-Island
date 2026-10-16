import * as Notifications from 'expo-notifications';
import { native, TimerInfo } from '../native';

/**
 * Timers store an absolute end timestamp natively (so the island shows the true remaining time after restarts) and
 * schedule an OS notification for the end, which fires even if the app is closed.
 */
export async function startTimer(label: string, minutes: number) {
  const endAt = Date.now() + minutes * 60000;
  const text = label.trim() || `${minutes} min timer`;
  const nid = await Notifications.scheduleNotificationAsync({
    content: { title: 'Timer finished', body: text, sound: true },
    trigger: { type: 'date', date: new Date(endAt) } as any,
  });
  return native.startTimer(text, endAt, nid);
}

export async function cancelTimer(t: TimerInfo) {
  try { if (t.notifId) await Notifications.cancelScheduledNotificationAsync(t.notifId); } catch { /* already fired */ }
  await native.cancelTimer(t.id);
}
