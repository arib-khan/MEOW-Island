import * as Notifications from 'expo-notifications';
import { addReminder, Reminder } from './db';
// OS-level scheduling (AlarmManager under the hood) => fires after app close/reboot; no JS timers involved.
export async function scheduleReminder(text: string, at: Date, repeat: Reminder['repeat']) {
  const trigger: any = repeat === 'daily' ? { type: 'daily', hour: at.getHours(), minute: at.getMinutes() }
    : repeat === 'weekly' ? { type: 'weekly', weekday: at.getDay()+1, hour: at.getHours(), minute: at.getMinutes() }
    : { type: 'date', date: at };
  const id = await Notifications.scheduleNotificationAsync({ content: { title: 'Reminder', body: text, categoryIdentifier: 'reminder', sound: true }, trigger });
  return addReminder(text, at.getTime(), repeat, id);
}
export async function setupReminderActions() {
  await Notifications.setNotificationCategoryAsync('reminder', [
    { identifier: 'snooze', buttonTitle: 'Snooze 10m' }, { identifier: 'done', buttonTitle: 'Done' }]);
}
export const snooze = (text: string, min = 10) => scheduleReminder(text, new Date(Date.now()+min*60000), 'none');
