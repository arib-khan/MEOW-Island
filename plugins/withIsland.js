// Expo config plugin: manifest permissions/services + copies Kotlin sources + registers package.
const { withAndroidManifest, withDangerousMod, withMainApplication, withAndroidStyles } = require('@expo/config-plugins');
const fs = require('fs'), path = require('path');
const PKG = 'com.smartisland.app';
module.exports = (config) => {
  config = withAndroidManifest(config, (c) => {
    const m = c.modResults.manifest;
    const perms = ['SYSTEM_ALERT_WINDOW','FOREGROUND_SERVICE','FOREGROUND_SERVICE_SPECIAL_USE','POST_NOTIFICATIONS','RECEIVE_BOOT_COMPLETED','SCHEDULE_EXACT_ALARM','PACKAGE_USAGE_STATS','ACTIVITY_RECOGNITION','FOREGROUND_SERVICE_HEALTH'];
    m['uses-permission'] = m['uses-permission'] || [];
    perms.forEach(p => { if (!m['uses-permission'].some(x => x.$['android:name']===`android.permission.${p}`)) m['uses-permission'].push({$:{'android:name':`android.permission.${p}`}}); });
    const app = m.application[0]; app.service = app.service || [];
    app.service.push({$:{'android:name':'.IslandService','android:foregroundServiceType':'specialUse','android:exported':'false'},property:[{$:{'android:name':'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE','android:value':'floating overlay'}}]});
    app.service.push({$:{'android:name':'.MediaListener','android:exported':'true','android:label':'Meow Island','android:permission':'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'},'intent-filter':[{action:[{$:{'android:name':'android.service.notification.NotificationListenerService'}}]}]});
    // Narrow package visibility: only launchable apps (MAIN/LAUNCHER), no QUERY_ALL_PACKAGES.
    m.queries = m.queries || [];
    m.queries.push({ intent: [{ action: [{ $: { 'android:name': 'android.intent.action.MAIN' } }], category: [{ $: { 'android:name': 'android.intent.category.LAUNCHER' } }] }] });
    // Step sensor is optional hardware; do not hide the app from devices without it.
    m['uses-feature'] = m['uses-feature'] || [];
    m['uses-feature'].push({ $: { 'android:name': 'android.hardware.sensor.stepcounter', 'android:required': 'false' } });
    // Optional low-power background step counting (health-type foreground service; needs ACTIVITY_RECOGNITION).
    app.service.push({ $: { 'android:name': '.StepService', 'android:exported': 'false', 'android:foregroundServiceType': 'health' } });
    // Optional, inert accessibility service: only hosts the topmost (accessibility-overlay) window layer.
    app.service.push({ $: { 'android:name': '.MeowAccessibilityService', 'android:exported': 'true', 'android:label': 'Meow Island', 'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE' },
      'intent-filter': [{ action: [{ $: { 'android:name': 'android.accessibilityservice.AccessibilityService' } }] }],
      'meta-data': [{ $: { 'android:name': 'android.accessibilityservice', 'android:resource': '@xml/meow_accessibility_config' } }] });
    app.receiver = app.receiver || [];
    const widget = (cls, label, xml) => ({ $: { 'android:name': '.' + cls, 'android:exported': 'true', 'android:label': label },
      'intent-filter': [{ action: [{ $: { 'android:name': 'android.appwidget.action.APPWIDGET_UPDATE' } }] }],
      'meta-data': [{ $: { 'android:name': 'android.appwidget.provider', 'android:resource': '@xml/' + xml } }] });
    app.receiver.push(widget('MeowClockWidget', 'Meow Clock', 'meow_widget_clock_info'));
    app.receiver.push(widget('MeowRoundClockWidget', 'Meow Round Clock', 'meow_widget_round_info'));
    app.receiver.push(widget('MeowHeatWidget', 'Meow Step Heatmap', 'meow_widget_heat_info'));
    app.receiver.push(widget('MeowStepCardWidget', 'Meow Step Goals', 'meow_widget_stepcard_info'));
    app.receiver.push(widget('MeowMusicWidget', 'Meow Dancing Cat', 'meow_widget_music_info'));
    app.receiver.push({ $: { 'android:name': '.MeowMusicActionReceiver', 'android:exported': 'false' } });
    app.receiver.push({ $: { 'android:name': '.WaterReceiver', 'android:exported': 'false' } });
    app.receiver.push({ $: { 'android:name': '.HealthReceiver', 'android:exported': 'false' } });
    app.receiver.push({ $: { 'android:name': '.BootReceiver', 'android:exported': 'true' }, 'intent-filter': [{ action: [{ $: { 'android:name': 'android.intent.action.BOOT_COMPLETED' } }] }] });
    return c;
  });
  // Branded launch window (dark background + cat logo) shown before React Native draws its first frame.
  config = withDangerousMod(config, ['android', (c) => {
    const res = path.join(c.modRequest.platformProjectRoot, 'app/src/main/res');
    fs.mkdirSync(path.join(res, 'drawable'), { recursive: true });
    fs.mkdirSync(path.join(res, 'drawable-nodpi'), { recursive: true });
    // Home-screen widget layouts, drawables, provider XML and picker previews (kept as real files in plugins/widgets/res).
    const copyDir = (from, to) => { fs.mkdirSync(to, { recursive: true }); for (const e of fs.readdirSync(from, { withFileTypes: true })) { const a = path.join(from, e.name), b = path.join(to, e.name); if (e.isDirectory()) copyDir(a, b); else fs.copyFileSync(a, b); } };
    const wres = path.join(c.modRequest.projectRoot, 'plugins/widgets/res');
    if (fs.existsSync(wres)) copyDir(wres, res);
    const logo = path.join(c.modRequest.projectRoot, 'assets/splash-icon.png');
    if (fs.existsSync(logo)) fs.copyFileSync(logo, path.join(res, 'drawable-nodpi/meow_logo.png'));
    fs.writeFileSync(path.join(res, 'drawable/meow_splash.xml'),
      '<?xml version="1.0" encoding="utf-8"?>\n<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n' +
      '  <item><shape android:shape="rectangle"><solid android:color="#0E0B16"/></shape></item>\n' +
      '  <item android:gravity="center" android:width="140dp" android:height="140dp"><bitmap android:src="@drawable/meow_logo" android:gravity="fill"/></item>\n</layer-list>\n');
    fs.writeFileSync(path.join(res, 'drawable/ic_meow.xml'),
      '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n' +
      '  <path android:fillColor="#FFFFFFFF" android:pathData="M3.5,9.5 L4.5,2.5 L10,6.2 Q12,5.8 14,6.2 L19.5,2.5 L20.5,9.5 Q21,12 20.5,14 Q19,21 12,21 Q5,21 3.5,14 Q3,12 3.5,9.5 Z"/>\n</vector>\n');
    return c;
  }]);
  config = withAndroidStyles(config, (c) => {
    const styles = c.modResults.resources.style || [];
    const app = styles.find((x) => x.$ && x.$.name === 'AppTheme');
    if (app) {
      app.item = (app.item || []).filter((i) => i.$.name !== 'android:windowBackground');
      app.item.push({ $: { name: 'android:windowBackground' }, _: '@drawable/meow_splash' });
    }
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
