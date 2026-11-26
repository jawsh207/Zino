# How Zino works

This is a map of the code: what each part does, how the parts talk to each other, and where to look. Every source file also starts with a short description of its own.

All code lives in `app/src/main/java/app/struthio/nostr/`, in five folders:

| Folder | What's in it |
|---|---|
| `core/` | The Nostr protocol as plain Kotlin: events, keys, encryption, shareable codes, and reading post content. No Android code, so it's all unit tested. |
| `net/` | Talking to the outside world: relays, WebSockets, HTTPS, and the app log. |
| `data/` | The app's state and actions: the account, the database, feeds, messages, the wallet, uploads and notifications. |
| `ui/` | The screens, in Jetpack Compose. |
| `widget/` | The home screen widgets, drawn with Android's RemoteViews. |

## The big picture

```
 screens (ui/)  ──call──▶  Core (data/Core.kt)  ──▶  RelayPool (net/)  ──▶  relays
      ▲                        │      ▲                    │
      │                        ▼      └──── events ────────┘
      └──── redraw on ──  Store (SQLite)
            "changes"
```

1. Screens never talk to relays themselves. They call functions on **Core**, like `post`, `react`, `zap` or `sendMessage`, and read its state.
2. Core asks **RelayPool** for events. Every event that comes back is checked (id and signature) and handed to `Core.ingest`.
3. Events are stored in batches in **Store**, the SQLite database. After each batch, Core sends a signal on `changes`.
4. Screens read from the database through `liveQuery` (in `ui/Common.kt`), which runs its query again when `changes` fires. So what you see is always what's stored, and it shows up at once when the app opens, even offline.

`ZinoApp` keeps the list of accounts and one `Core` for the account in use. `MainActivity` is the only Android screen; `ui/App.kt` draws everything inside it. `Shortcuts.kt` holds the launcher shortcuts and the actions they and the widgets open the app with.

## Accounts

Each account on the phone has its own database, settings and secrets files (`data/Accounts.kt` names them), so accounts never see each other's messages, wallet or keys. The first account uses the file names Zino had before accounts existed, so updating keeps everything.

- **Switching.** `ZinoApp.switchTo` closes the current `Core` (its relays, subscriptions and network watch) and opens a new one on the other account's files. The activity draws the app inside `key(core)`, so every screen starts fresh for the new account.
- **Adding** creates an empty account and switches to it, which shows sign-in. Sign-in can go back to the previous account, and the same person can't be added twice.
- **Signing out** clears that account. If there are others, it's removed and another one takes over. Its files are deleted a little later, once the closed `Core` has let go of them.
- **Notifications** are checked for every signed-in account (see Notifications below).

## Relays

`net/RelayPool.kt` keeps one connection per relay, opened only while something needs it and only while the app is on screen. Connections close shortly after the app goes away.

- **Subscriptions.** A `Subscription` asks some relays for events matching some filters. Requests go out one at a time and only a few stay open per relay, because relays limit both.
- **Posting.** `publish` sends an event to a set of relays and collects each one's answer.
- **Sign-in (NIP-42).** Only when a relay demands it. Requests waiting on sign-in are sent again once it succeeds.
- **Failing relays rest.** A relay that keeps failing, refuses us or rate-limits us is left alone for a while, so a dead relay doesn't slow everything down. A failure while the phone has no network doesn't count against any relay. A relay that answers with an HTTP error (a 503, say) keeps its pause through a network change, since its server was reached. One that turns us away, isn't a relay at all, has a name that no longer exists, or gives server errors try after try rests for half an hour, then twice as long each time after that, up to a day. Those rests are kept in `net/RelayStats.kt`, so they survive a restart, and end once the relay lets us in, including from a relay screen check. After a failed try, a relay isn't dialled again for a few seconds, however many requests want it.
- **Relays that won't take posts.** A relay that refuses a post with `restricted:`, or with `blocked:` and a reason like payment or writes being off, isn't sent more posts for half an hour. Posts to it fail at once with what it said. Our relay list (kind 10002) goes only to the inboxes of tagged people that took the post.
- **Reconnects** ask only for what's newer than what the relay already sent.
- **Checking the network on return.** Android can hold back network news while the app sits frozen in the background, so on coming to the front (and on pull to refresh) `Core.checkNetwork` asks Android directly and tells the relays, rather than trusting what they last heard.
- **Relays that can't do something** aren't asked again that session: one that answers COUNT with a notice about an unknown command, or that says it accepts no requests at all (a write-only relay).
- **Data use and uptime** per relay are counted in `net/RelayStats.kt` and shown on the relay screens.
- **Not now or no?** When a post fails, `PublishResult.retryable` says whether trying later could help: no relay answered, the connection dropped, or the answers were "rate-limited:" or "error:" (NIP-01). An outright refusal like "blocked:" is shown as an error instead.

- **Comparing lists (NIP-77).** `RelayPool.missing` runs negentropy (`core/Negentropy.kt`) with one relay: the two sides trade fingerprints of ranges of events until each knows what the other lacks, without sending the events. It returns the ids Zino is missing, or null if the relay doesn't support it, so the caller can fall back to a plain REQ. Many relays run software that can do this but have it turned off; they answer with a notice, which ends the run at once. That relay is then remembered in `net/RelayStats.kt` for a week and not asked again, so it gets a plain REQ straight away. Notices from relays are kept in the relay's problem list.

`net/WebSocket.kt` is Zino's own WebSocket client (TLS, handshake, frames), so no networking library is needed. The only libraries Zino uses beyond Compose are libsecp256k1, for keys and signatures, and ZXing, for QR codes.

## Which relays to ask

- **Your relays** come from your relay list (NIP-65), with Zino's defaults when you have none.
- **The Following feed uses the outbox model.** `Core.outbox` picks the fewest relays that reach everyone you follow, using each person's own write relays, so their posts are found where they publish them.
- **Messages** go to each person's messaging relays (kind 10050).
- **Search** and **profile lookup** have their own relay lists, which you can edit.

## Storage

`data/Store.kt` is a SQLite database with these main tables:

- `events`: every event kept, with replaceable events reduced to their newest version
- `refs`: an index of tag values, for finding replies, reactions, zaps and votes
- `zaps`: checked zap receipts
- `dms`, `dm_reactions`: decrypted private messages and their reactions
- `wraps`: which gift wraps were already opened, and when they were made
- `outbox`: signed events waiting for a connection
- `reads`: what you've already seen, for unread markers

The cache is pruned at start past the newest 20,000 events: ordinary events from people you don't follow go first, and posts by people you follow go after 90 days. Your own events, bookmarks, deletions, profiles and lists always stay.

Deletions (NIP-09) remove the events they point at, but only when the author matches. A deletion that arrives first also stops the deleted event from being stored later.

Secrets, like your private key and wallet link, are not in the database. `data/Secure.kt` encrypts them with a key kept in Android's hardware Keystore.

## Feeds

`data/Feed.kt` runs one scrolling list of posts. Each kind of feed (Following, Trending, Global, your relays, a profile, a hashtag, search, bookmarks) decides which relays to ask and what to keep.

A feed:
1. shows what's stored straight away: Following from the people you follow, Global and Relays from the posts first seen on those relays, Trending from the last list it worked out,
2. asks relays for anything newer and keeps listening,
3. loads older posts as you near the end of the list.

A feed says "Nothing here yet" only after its relays have answered (or 10 seconds have passed), not while they're still loading. The Home tab's feeds are kept in `Core.homeFeeds`, so switching tabs and coming back shows the same posts.

New posts that arrive while you're scrolled down wait behind the "new posts" pill in `ui/Home.kt`.

Trending counts reactions and boosts from the last day. With the trusted network setting on, Global and Trending only show people you follow and the people they follow. That network is worked out once a day from the follow lists of the people you follow. Those lists are heavy, so they're asked from the lookup relays first, and from general relays only for the lists the lookup relays didn't have.

## Posts

The rules for reading a post are in `core/`:

- `Protocols.kt` finds links, hashtags, mentions, pictures, videos, invoices, polls and threads in a post.
- `Posts.kt` reads picture posts, video posts and articles.
- `Markdown.kt` reads article text.

Pictures in a post show as a grid (`ImageGrid` in `ui/Note.kt`): one on its own, two side by side, three as one large and two small, four or more two by two, with a "+N" tile in feeds. Links with no file ending and a long hash-like name, as Blossom servers give, are checked once for their type when link previews are on, and shown as pictures if they are.

The screens that draw posts are `ui/Note.kt` (every post card), `ui/Thread.kt` (a post and its replies), `ui/Article.kt`, `ui/Poll.kt`, `ui/Invoice.kt`, `ui/Embed.kt` and `ui/Viewer.kt`.

Videos play in `NetVideoView` (`ui/Viewer.kt`): Android's `MediaPlayer` drawn on a `TextureView`. Its controls (`VideoControls`) are drawn in Compose inside the card, so they scroll with it; Android's `MediaController` floats in a window of its own and stays put. A video stops once less than a fifth of its card is on screen, when the screen changes, or when the app is left. It isn't `VideoView`, which resets its player on the main thread when a video scrolls away. That reset waits for the media server to drop its connection, and on a bad network that waits behind a stuck name lookup, long enough for Android to close the app as not responding. Opening the address, attaching the picture and the final release all run on a thread of each video's own.

## Sending without a connection

When a post, reaction, vote or message can't reach any relay for now, `Core.publish` (or `deliver`, for messages) keeps the signed event in the `outbox` table and shows it in the app as if sent. A snackbar says it will go out later.

`Core.sendWaiting` sends what's waiting:
- when the network comes back,
- when the account's session starts,
- every two minutes while the app is open and something is waiting,
- and during each background notification check.

If an event still can't go and no relay is connected, it stops there, since the phone is likely offline. If other relays are connected, only that event waits and the rest go ahead. Events that relays refuse outright are dropped, and so is anything still unsent after three days. The exact same signed event is sent each time, so a relay that got an earlier try keeps only one copy.

## Private messages

These use NIP-17, and the code is in `data/Core.kt` (`deliver`, `sendMessage`, `receiveWrap`) and `ui/Messages.kt`.

**Sending.** A message starts as an unsigned "rumor" (kind 14 for text, 15 for a file, 7 for a reaction). For each person in the chat, the rumor is sealed and gift-wrapped (NIP-59), encrypted with NIP-44, and sent to that person's messaging relays. You get a wrapped copy too, sent to your own messaging relays, so the message shows on your other devices.

**Receiving.** Gift wraps addressed to you are opened once, then stored as plain messages on the phone. Each wrap's id and time are remembered, so it's never opened twice.

**Catching up.** On start, Zino compares the wraps it has from the last few days with each messaging relay (NIP-77), fetches only the missing ones by id, then listens for new ones. Notifications catch up the same way over two weeks, through the same helper (`Core.compareAndFetch`). Relays without NIP-77 are asked the old way, from a few days before the last message, which downloads some messages again.

**Files** are encrypted before they're uploaded, and decrypted only in memory when shown.

## Signing

`core/Event.kt` defines `Signer`. Everything that signs or encrypts goes through it, so the rest of the app doesn't care where the key is. There are four kinds:

| Kind | Where the key is | File |
|---|---|---|
| Local key | On the phone, encrypted by the Keystore | `core/Event.kt`, `data/Secure.kt` |
| Signer app (NIP-55) | In an app like Amber | `data/Secure.kt` |
| Remote signer (NIP-46) | In another app or service, reached over relays | `data/RemoteSigner.kt` |
| Read-only (npub) | Nowhere: browsing only | `core/Event.kt` |

Password-protected key backups (NIP-49, `ncryptsec`) are in `core/KeyBackup.kt`.

## Wallet and zaps

The wallet uses Nostr Wallet Connect (NIP-47) and lives in `data/Wallet.kt` and `ui/Wallet.kt`. Zino holds no money. It sends encrypted requests to your own wallet over relays.

A zap (NIP-57) works like this: `Core.zap` gets an invoice from the recipient's lightning address (`net/Lnurl.kt`), then pays it through the wallet. A post with zap splits gets one invoice per person, each for their share. If no wallet is connected, the invoice opens in a wallet app instead.

Zap receipts only count after they're checked against the key of the lightning service that issued them.

## Notifications

Zino uses no push service. `data/Notifier.kt` asks Android's JobScheduler to run a short check every so often (15 minutes by default). There's one job for the whole app, set to the most frequent interval any signed-in account wants; each account skips runs that come sooner than its own setting, and an account with checks off is never checked. Each check runs `Core.backgroundCheck`:

1. connect to relays,
2. look for new replies, mentions, reactions, zaps and messages (and new posts for the Following widget, if one is placed),
3. show notifications,
4. disconnect.

Message notifications never say who sent the message or what it says.

**Every account.** `ZinoApp.checkAllAccounts` runs the check for the account in use and, side by side, for each other signed-in account through a short-lived `Core` of its own, closed afterwards. Each account has its own summary and message notifications, so one can't replace another's. With more than one account, each notification names its account, and tapping one switches to that account first (`Notifier.EXTRA_ACCOUNT`).

**Shrinking the database.** Android's SQLite trims the database file as rows are deleted (auto-vacuum), so space from cleanups mostly comes back by itself. As a safety net, at most once a week a background check runs `Store.compactIfWasteful`: if 8 MB or more of empty space is left inside the file anyway, it rebuilds the file (VACUUM). That runs in the background because the rebuild would slow the app down while it's open.

## Shortcuts and widgets

**Long-press shortcuts.** Holding Zino's icon offers New post, Messages and Scan QR code. `Shortcuts.install` adds them from code each time the app starts, replacing them only when their names change, since a shortcut written in XML must name the package and test builds have a `.debug` one. Each opens `MainActivity` with an action, which `Shortcuts.linkOf` turns into a `zino:` link (`zino:compose`, `zino:messages`, `zino:scan`, `zino:wallet`). It travels to `ui/App.kt` through the same `deepLink` as a `nostr:` link. Scan QR code opens the scanner over the app and opens what it reads like any other link: a profile, a post or a wallet connection.

**Widgets** are plain app widgets (`AppWidgetProvider` and `RemoteViews`), not Jetpack Glance, so they need no library. They show the account in use and never go to the network themselves:

| Widget | File | Shows |
|---|---|---|
| Write a post | `widget/ComposeWidget.kt` | A pill that opens a new post |
| Wallet | `widget/WalletWidget.kt` | The last balance the wallet gave, when, and an eye button that hides it. Tapping opens the Wallet tab. |
| Following | `widget/FeedWidget.kt` | The newest 20 stored posts from people you follow, without muted ones. Tapping a post opens it; the pencil writes a post. |

- **Redrawing.** `Widgets.update` (in `widget/Widgets.kt`) redraws them all when the app goes to the background, when the account changes, when the wallet answers or is removed, and after each background check.
- **The balance** is saved in the account's settings by `Wallet` each time the wallet answers `get_balance`, since asking the wallet means relays and a wait. Hiding it is a setting of the widgets, not of an account.
- **The Following list** is read from the database by `FeedService`, which Android runs in Zino's process. Profile pictures come only from the picture cache, so anyone whose picture was never shown gets a plain circle. While a Following widget is placed, each background check also asks up to four relays for posts newer than the newest one stored (`Core.fetchWidgetFeed`), picking them from relay lists already on the phone. So the widget keeps up while the app is closed, but only as often as checks run, and not at all with checks turned off.
- **Taps** carry the account (`Notifier.EXTRA_ACCOUNT`), so a widget tap opens that account even if another is in use by then.

## Muting

The mute list (NIP-51, kind 10000) can hold people (`p`), words (`word`) and hashtags (`t`), in public tags or in its encrypted private part. `Core.applyMutes` reads all three. `Core.hides` says whether a post is hidden: by a muted person, by a muted hashtag in its tags or text, or by a muted word. Words match whole words, ignoring case, so muting "art" doesn't hide "start". Feeds, Trending, threads (except the post you opened) and notifications all leave hidden posts out. `ui/Mutes.kt` adds and removes words and hashtags; changes go on the public part of the newest mute list from your relays, keeping everything else on it.

## Media

- **Uploads.** `data/Media.kt` uploads to Blossom servers (NIP-B7). Photos are turned upright and re-encoded first, which removes location and other hidden details.
- **Blurred previews.** Posts can include a blurhash for each picture in its imeta tag (NIP-92). `core/Blurhash.kt` paints it as a tiny picture, which `NetImage` stretches over the picture's space until the real one has loaded.
- **Downloads.** Pictures are fetched with `net/Http.kt`. They're cached on disk, and decoded pictures are kept in memory (`Images` in `ui/Common.kt`).

## Text and languages

Every word Zino shows lives in `app/src/main/res/values/strings.xml`. Code asks for text with `str(R.string.name)`, or `plural(R.plurals.name, count)` for text that changes with a number. Both are in `Texts.kt`, and unlike Compose's own `stringResource` they work anywhere: in click handlers, in Core's error messages and in notifications.

A translation is a copy of that file in a `values-xx` folder, like `values-de`. Android then picks the language from the phone's settings, or from Zino's own entry in the system's per-app language setting. Changing the language restarts the screen, so nothing keeps the old language.

Some text still comes in English from places that can't use Android resources: what relays say about themselves, the relay details read by `core/Protocols.kt`, the account check report, and messages from `net/`.

## Screens

`ui/App.kt` holds the four tabs (Home, Messages, Wallet, Notifications) and a stack of screens opened on top of them (`Screen` and `Nav`).

| Screen | File |
|---|---|
| Home feeds, search bar | `ui/Home.kt` |
| Post cards, zap sheet | `ui/Note.kt`, `ui/NoteMenu.kt` |
| Threads, hashtags, writing a post | `ui/Thread.kt`, `ui/Composer.kt`, `ui/Attach.kt` |
| Profiles, follow lists, search | `ui/Profile.kt` |
| Messages and chats | `ui/Messages.kt` |
| Wallet | `ui/Wallet.kt` |
| Notifications | `ui/Alerts.kt` |
| Relays and relay details | `ui/Relays.kt` |
| Settings, accounts, profile editing, sign-in | `ui/Settings.kt` |
| Muted words and hashtags | `ui/Mutes.kt` |
| QR scanning and codes | `ui/Qr.kt` |
| Theme, fonts, colours | `ui/Theme.kt` |
| Shared pieces (avatars, images, rows, times) | `ui/Common.kt`, `ui/Bars.kt` |

## Tests and checks

- **Unit tests** are in `app/src/test/`. They cover the protocol code in `core/` (using the published NIP test vectors where they exist), and the relay pool, WebSocket client and relay stats in `net/`. Tests in `data/` run with Robolectric, which gives them Android's real SQLite on the computer. They cover the database (replaceable events, deletions, counts, messages, the outbox), accounts, opening gift wraps, and keeping posts made without a connection. `ShortcutsTest` checks which links the launcher shortcuts open.
- **Device checks** are in `scripts/`. They run by hand from GitHub Actions on an emulator and sign in read-only, so they never publish anything:
  - `smoke.sh` walks through the main screens.
  - `kinds-check.sh` checks how the different post kinds are drawn.
  - `embed-check.sh` checks video embeds.
  - The Negentropy check workflow compares a list of public notes with a real relay (relay.nostr.net by default), to make sure Zino's NIP-77 matches what relays run.
- **`docs-check.py`** runs on every push and fails the build if a file, class or function has no description. See [CONTRIBUTING.md](CONTRIBUTING.md).
