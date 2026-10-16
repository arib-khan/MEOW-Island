// Expo config plugin: manifest permissions/services + copies Kotlin sources + registers package.
const { withAndroidManifest, withDangerousMod, withMainApplication } = require('@expo/config-plugins');
const fs = require('fs'), path = require('path');
const PKG = 'com.smartisland.app';
module.exports = (config) => {
  config = withAndroidManifest(config, (c) => {
    const m = c.modResults.manifest;
    const perms = ['SYSTEM_ALERT_WINDOW','FOREGROUND_SERVICE','FOREGROUND_SERVICE_SPECIAL_USE','POST_NOTIFICATIONS','RECEIVE_BOOT_COMPLETED','SCHEDULE_EXACT_ALARM','PACKAGE_USAGE_STATS'];
    m['uses-permission'] = m['uses-permission'] || [];
    perms.forEach(p => { if (!m['uses-permission'].some(x => x.$['android:name']===`android.permission.${p}`)) m['uses-permission'].push({$:{'android:name':`android.permission.${p}`}}); });
    const app = m.application[0]; app.service = app.service || [];
    app.service.push({$:{'android:name':'.IslandService','android:foregroundServiceType':'specialUse','android:exported':'false'},property:[{$:{'android:name':'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE','android:value':'floating overlay'}}]});
    app.service.push({$:{'android:name':'.MediaListener','android:exported':'true','android:label':'Smart Island','android:permission':'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'},'intent-filter':[{action:[{$:{'android:name':'android.service.notification.NotificationListenerService'}}]}]});
    // Narrow package visibility: only launchable apps (MAIN/LAUNCHER), no QUERY_ALL_PACKAGES.
    m.queries = m.queries || [];
    m.queries.push({ intent: [{ action: [{ $: { 'android:name': 'android.intent.action.MAIN' } }], category: [{ $: { 'android:name': 'android.intent.category.LAUNCHER' } }] }] });
    app.receiver = app.receiver || [];
    app.receiver.push({ $: { 'android:name': '.BootReceiver', 'android:exported': 'true' }, 'intent-filter': [{ action: [{ $: { 'android:name': 'android.intent.action.BOOT_COMPLETED' } }] }] });
    return c;
  });
  config = withDangerousMod(config, ['android', (c) => {
    const dest = path.join(c.modRequest.platformProjectRoot,'app/src/main/java',...PKG.split('.'));
    fs.mkdirSync(dest,{recursive:true});
    const src = path.join(c.modRequest.projectRoot,'plugins/kotlin');
    fs.readdirSync(src).forEach(f => fs.copyFileSync(path.join(src,f), path.join(dest,f)));
    return c;
  }]);
  return withMainApplication(config, (c) => {
    let s = c.modResults.contents;
    if (!s.includes('IslandPackage()')) {
      const applyRe = /(PackageList\(this\)\.packages\.apply\s*\{)/;
      const plainRe = /PackageList\(this\)\.packages(?!\.apply)/;
      if (applyRe.test(s)) s = s.replace(applyRe, '$1\n      add(IslandPackage())');
      else if (plainRe.test(s)) s = s.replace(plainRe, 'PackageList(this).packages.apply { add(IslandPackage()) }');
      else throw new Error('withIsland: PackageList(this).packages not found in MainApplication; add add(IslandPackage()) manually');
    }
    c.modResults.contents = s; return c;
  });
};
