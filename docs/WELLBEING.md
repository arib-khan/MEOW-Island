# Wellbeing features (Reels/Shorts counter + blocker, adult website filter)

All three features are opt-in, local-only, and configured in the **Wellbeing** tab.

## Setup
| Feature | Android permission | How |
|---|---|---|
| Counter and Reels/Shorts blocker | Accessibility service "Meow Island Reels & Shorts guard" (separate from the inert island service) | Wellbeing tab → Set up the guard → Android Settings → Accessibility. On sideloaded APKs: App info → ⋮ → Allow restricted settings first. No overlay permission needed (windows use the accessibility overlay layer). |
| Adult website filter | VPN consent (official dialog) | Toggle it on; Android asks. Keeps running as a low-priority foreground service. |
| After reboot | none extra | Accessibility service is restored by Android; VPN restarts from BootReceiver only if consent is still valid. |

## How it works
* **Detection** (`ShortsLogic.kt`, `ShortsGuardService.kt`): looks up a few view ids in Instagram/YouTube windows (`ShortsRules`). An item counts after it has stayed on screen 300 ms, items seen in the last 90 s are never recounted. If the app exposes no item text, it falls back to debounced scroll counting and marks the day "≈".
* **Blocking**: full-screen shield (accessibility overlay, status bar and nav bar left visible), optionally steering out of the feed (app Home tab → Back → Android Home). No timeout. Non-strict mode has a timed Pause, strict mode does not.
* **PIN**: enforced natively for any change that weakens protection. It is a speed bump, not security.
* **Adult filter** (`AdultFilterService.kt`, `DnsPacket.kt`, `DomainBlocklist.kt`): VPN owns two virtual DNS addresses (IPv4+IPv6) only; blocked names get NXDOMAIN, everything else is forwarded to the network's own DNS (or Cloudflare family / custom). Subdomains match; allow-list beats block for the more specific name. Blocklist: bundled StevenBlack porn hosts (MIT) + adult-only TLDs, updatable over https.

## Known limits
* View ids are app internals and can change with any Instagram/YouTube release; detection then silently stops (nothing counted/blocked). Edit `ShortsRules`. These ids were NOT verified against live apps in development.
* Turning the accessibility service off in Android settings disables counting/blocking; the app shows a warning but cannot prevent it.
* DNS filtering cannot catch direct-IP connections, apps with built-in DoH/DoT, new domains, cached content or in-app media; Private DNS (specific host) or another VPN bypasses/replaces it. TCP DNS and very large UDP answers are not handled.
* Play Store: accessibility use for a non-accessibility tool and a special-use foreground service need policy declarations.

## Manual device test plan
1. Enable guard; open Instagram Reels, swipe 5 reels: pill appears only in the feed, count rises by 5; DMs/Home/Stories show no pill. Same for YouTube Shorts vs a regular video.
2. Swipe back and forth between two reels: no double counting. Force-stop and reopen Meow Island: counts persist. Reboot: counts persist.
3. Enable IG block (strict): open Reels → shield appears, swipes/taps do nothing, nav bar works, Back button leaves the feed, shield disappears. Reopen Reels → shield again. Non-strict: Pause works.
4. Set PIN; try to disable blocking → PIN prompt; wrong PIN rejected; 5 wrong → 1 min lock.
5. Revoke accessibility: status shows warning, no crash. Re-enable: works again.
6. Adult filter on: consent dialog; browse a listed domain in Chrome and Firefox → fails to load; normal sites load; `ping`/DNS in other apps fine. Check IPv6-only network. Turn on another VPN → status explains. Set Private DNS to a hostname → warning shown.
7. Reboot with filter enabled → key icon returns. Update blocklist → count changes.
8. Check battery stats after a day; the service should be idle outside Instagram/YouTube.
