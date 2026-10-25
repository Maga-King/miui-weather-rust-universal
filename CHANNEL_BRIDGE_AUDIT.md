# Platform bridge audit — 2026-10-24

The AOT scan yields candidates, not a complete coverage count: pool references
can be callbacks, labels or methods that are never invoked on this build.

## Implemented in the channel-audit APKs

- Legacy and OS4 typhoon, rainfall and AQI Activity dispatch, original resources,
  city JSON transport and mode extras. Typhoon opened twice on device bf69d9b2
  (Android 17 / OS4); screenshot and logs are under `.build/channel-audit/`.
- Typed package queries, launch-intent serialization, standard Activity launches
  and Activity results; standard location/app/notification settings routing.
- HTTP(S)/weather-video navigation to the existing in-app web Activity.
- Toast, standard Android haptics and text clipboard read/write.
- Package-scoped outgoing broadcasts and weather-data change import scheduling.
- Actual network-type lookup, window color mode and typed platform errors.

Current on-device evidence covers launch and the legacy typhoon page. Other
added methods have compiled, but are not all individually UI-tested. Existing
speech, notification, permission, broadcast receiver and shortcut bridges are
separate implementations and were not newly added by this audit.

## Remaining candidates / platform boundaries

- `TranslateService` start/stop: original weather's city-name localization after
  a language change. Do not substitute a widget refresh and claim equivalence.
- `clear_application_user_data`: the application-data reset API. It needs a
  deliberately confirmed reset path; never invoke it during regression tests.
- ContentResolver `call` / `action_result`: Xiaomi assistant integration;
  determine the receiving authority and actual protocol before implementing.
- Advertisement/store download, pause/resume and dislike-window methods:
  vendor-service features, not ordinary weather-page dependencies. No success
  is fabricated and no third-party downloads are initiated.

There were no matching missing-method/fatal errors in the captured launch and
typhoon test window. This is not a claim that every reachable path is complete.
