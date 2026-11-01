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
