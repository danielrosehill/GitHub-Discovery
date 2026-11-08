# GitHub API notes (verified 2026-11-01 against api.github.com)

Findings this app depends on, with how each was checked.

## Search sorting
- GraphQL `search(type: REPOSITORY)` honours `sort:stars-desc`, `sort:forks-desc` and `sort:updated-desc` inside the query string. Verified with `topic:data-labeling`: the stars sort returned label-studio (28.6k), cleanlab and doccano in order.
- **`sort:created-desc` is silently ignored.** It returns an unsorted mix of 2020 and 2026 repos. REST `sort=` has no `created` option either. **Newest** is therefore emulated in `AppViewModel.loadNewest`: walk backwards through `created:START..END` windows. A window starts at 1 day, doubles when it has fewer than 15 results and halves when it has more than 200, up to 64 days. Each page is sorted client-side by `createdAt`.
- Search returns at most 1,000 results per query. The windowed Newest feed issues a new query per window, so it has no overall cap.

## Star Lists (GraphQL)
- Read: `viewer { lists { nodes { id name isPrivate items { nodes { ... on Repository { id } } } } } }`.
- **`Repository` has no field for "which of my lists contain this repo"** (checked by introspection; the only viewer fields are `viewerHasStarred` and similar). Membership is worked out by paging through every list's `items`.
- Write: `updateUserListsForItem(input:{itemId, listIds})` sets the **complete** set of lists for the item. Send all wanted list IDs, not a diff. Also available: `createUserList(input:{name, description, isPrivate})`, `updateUserList`, `deleteUserList`.
- Which OAuth scope the list mutations need for an OAuth-App token is **not yet confirmed**. The app requests `public_repo`. If list saving fails with a scope error, add `user` to `DeviceFlow.SCOPE`.

## Device Flow
- OAuth App client ID `Ov23liXxSmC0e2T1953G` ("GitHub Discovery", owned by danielrosehill), with Device Flow enabled. `POST https://github.com/login/device/code` returned a `user_code` on 2026-11-01.
- No client secret is used or needed. The secret generated in the OAuth App settings is not used by the app and is not in this repo.

## Topics
- GraphQL `topic(name).relatedTopics` came back empty for `data-labeling`, so it isn't used. Topic search uses REST `GET /search/topics?q=` (`is:featured` for the default list).

## Sign-in that never completed (fixed in v1.1, 2026-11-08)
- Symptom: Daniel approved the code on github.com, the app never signed in, and he read it as a misconfigured redirect.
- Checked on GitHub 2026-11-08: OAuth App 3982301 ("Discovery") has Device Flow enabled and redirect URI `https://github.com/danielrosehill/GitHub-Discovery`. Under Settings → Applications → Authorized OAuth Apps, "Discovery" **was** authorized but its token showed **"Never used"**. GitHub issued the grant and the app never collected it. The redirect URI plays no part in device flow, so it was ruled out.
- Cause (inferred from the code, not reproduced on the phone): `DeviceFlow.awaitToken` aborted on the first failed poll, which is common while the phone switches to the browser and back, and the device code lived only in the ViewModel, so if Android killed the app during approval, nobody was left polling.
- Fix: polls that hit an `IOException` are skipped. The pending device code is saved in prefs (`pending_device_code`) and `AppViewModel.init` resumes polling on relaunch until the code expires (15 min).
- Confirmed 2026-11-08 from the error Daniel saw on v1.0: after he approved the code, the app showed "Unable to resolve host "github.com": No address associated with hostname". That is an `UnknownHostException` (an `IOException`) thrown by a token poll while the app was in the background and Android had cut its network/DNS access. v1.0 surfaced it as a fatal sign-in error; v1.1 skips that poll and retries on the next interval, which succeeds once the app is back in the foreground.
