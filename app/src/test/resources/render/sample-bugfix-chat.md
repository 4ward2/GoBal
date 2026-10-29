# Fixed the crash on cold start

The *null pointer* happened because `SettingsRepositoryImpl` read `preferences_pb`
before the DataStore finished its first write. I did **not** touch the migration
code itself; the fix only changes read ordering.

Steps I took:

1. Added a guard in `ensureLoaded()`
2. Re-ran the suite 3 times to rule out flakiness
3. Confirmed with `adb logcat` that the race is gone

```bash
./gradlew :app:testFossDebugUnitTest --tests "*.SettingsRepositoryImplTest"
adb logcat -s MCP:Settings
```

Quick note on naming: don't confuse `snake_case_name` (a real identifier) with
`2 * 3` (just multiplication) or `*.kt` (a glob, not emphasis) — none of those
should turn into italics.

> Reviewer asked: "does this affect `*.json` config loading too?"
> No — that path never touches `SettingsRepositoryImpl`.

Here's the short patch:

```diff
- val prefs = dataStore.data.first()
+ val prefs = dataStore.data.filterNotNull().first()
```

One more thing worth flagging: the retry budget table below didn't change, I'm
just restating it so the next person doesn't have to dig:

| Attempt | Backoff |
|---|---|
| 1 | 200ms |
| 2 | 800ms |
| 3 | 3200ms |

- [x] Added regression test
- [ ] Still need to check this on a cold-boot real device 📱

Let me know if you want me to also backport this to the `legacy/` branch.
