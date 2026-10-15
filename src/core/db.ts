import * as SQLite from 'expo-sqlite';
export type Reminder = { id: number; text: string; at: number; repeat: 'none'|'daily'|'weekly'; done: number };
let db: SQLite.SQLiteDatabase;
export async function initDb() {
  db = await SQLite.openDatabaseAsync('island.db');
  await db.execAsync(`CREATE TABLE IF NOT EXISTS reminders(id INTEGER PRIMARY KEY, text TEXT, at INTEGER, repeat TEXT, done INTEGER DEFAULT 0, notifId TEXT);
  CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY, v TEXT);
  CREATE TABLE IF NOT EXISTS timers(id INTEGER PRIMARY KEY, label TEXT, endAt INTEGER, pausedLeft INTEGER);
  CREATE TABLE IF NOT EXISTS limits(pkg TEXT PRIMARY KEY, limitMin INTEGER);`);
}
export const getSetting = async <T,>(k: string, d: T): Promise<T> => { const r = await db.getFirstAsync<{v:string}>('SELECT v FROM settings WHERE k=?',[k]); return r ? JSON.parse(r.v) : d; };
export const setSetting = (k: string, v: unknown) => db.runAsync('INSERT OR REPLACE INTO settings VALUES(?,?)',[k, JSON.stringify(v)]);
export const listReminders = () => db.getAllAsync<Reminder>('SELECT * FROM reminders WHERE done=0 ORDER BY at');
export const addReminder = async (text: string, at: number, repeat: Reminder['repeat'], notifId: string) =>
  (await db.runAsync('INSERT INTO reminders(text,at,repeat,notifId) VALUES(?,?,?,?)',[text,at,repeat,notifId])).lastInsertRowId;
export const deleteReminder = (id: number) => db.runAsync('DELETE FROM reminders WHERE id=?',[id]);
export const completeReminder = (id: number) => db.runAsync('UPDATE reminders SET done=1 WHERE id=?',[id]);
export const saveTimer = (label: string, endAt: number) => db.runAsync('INSERT INTO timers(label,endAt) VALUES(?,?)',[label,endAt]);
export const activeTimers = () => db.getAllAsync<{id:number;label:string;endAt:number}>('SELECT * FROM timers WHERE endAt>?',[Date.now()]);
export const setLimit = (pkg: string, m: number) => db.runAsync('INSERT OR REPLACE INTO limits VALUES(?,?)',[pkg,m]);
export const getLimits = () => db.getAllAsync<{pkg:string;limitMin:number}>('SELECT * FROM limits');
