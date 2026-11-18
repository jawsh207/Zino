# How Zino works

This is a map of the code: what each part does, how the parts talk to each other, and where to look. Every source file also starts with a short description of its own.

All code lives in `app/src/main/java/app/struthio/nostr/`, in four folders:

| Folder | What's in it |
|---|---|
| `core/` | The Nostr protocol as plain Kotlin: events, keys, encryption, shareable codes, and reading post content. No Android code, so it's all unit tested. |
| `net/` | Talking to the outside world: relays, WebSockets, HTTPS, and the app log. |
| `data/` | The app's state and actions: the account, the database, feeds, messages, the wallet, uploads and notifications. |
| `ui/` | The screens, in Jetpack Compose. |

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

`ZinoApp` keeps the list of accounts and one `Core` for the account in use. `MainActivity` is the only Android screen; `ui/App.kt` draws everything inside it.

## Accounts

Each account on the phone has its own database, settings and secrets files (`data/Accounts.kt` names them), so accounts never see each other's messages, wallet or keys. The first account uses the file names Zino had before accounts existed, so updating keeps everything.

- **Switching.** `ZinoApp.switchTo` closes the current `Core` (its relays, subscriptions and network watch) and opens a new one on the other account's files. The activity draws the app inside `key(core)`, so every screen starts fresh for the new account.
- **Adding** creates an empty account and switches to it, which shows sign-in. Sign-in can go back to the previous account, and the same person can't be added twice.
- **Signing out** clears that account. If there are others, it's removed and another one takes over. Its files are deleted a little later, once the closed `Core` has let go of them.
- **Notifications** are checked for the account in use.

## Relays

`net/RelayPool.kt` keeps one connection per relay, opened only while something needs it and only while the app is on screen. Connections close shortly after the app goes away.

- **Subscriptions.** A `Subscription` asks some relays for events matching some filters. Requests go out one at a time and only a few stay open per relay, because relays limit both.
- **Posting.** `publish` sends an event to a set of relays and collects each one's answer.
- **Sign-in (NIP-42).** Only when a relay demands it. Requests waiting on sign-in are sent again once it succeeds.
- **Failing relays rest.** A relay that keeps failing, refuses us or rate-limits us is left alone for a while, so a dead relay doesn't slow everything down. A failure while the phone has no network doesn't count against any relay.
- **Reconnects** ask only for what's newer than what the relay already sent.
- **Data use and uptime** per relay are counted in `net/RelayStats.kt` and shown on the relay screens.
- **Not now or no?** When a post fails, `PublishResult.retryable` says whether trying later could help: no relay answered, the connection dropped, or the answers were "rate-limited:" or "error:" (NIP-01). An outright refusal like "blocked:" is shown as an error instead.

- **Comparing lists (NIP-77).** `RelayPool.missing` runs negentropy (`core/Negentropy.kt`) with one relay: the two sides trade fingerprints of ranges of events until each knows what the other lacks, without sending the events. It returns the ids Zino is missing, or null if the relay doesn't support it, so the caller can fall back to a plain REQ. Many relays run software that can do this but have it turned off; they answer with a notice, which ends the run at once. Notices from relays are kept in the relay's problem list.

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

Trending counts reactions and boosts from the last day. With the trusted network setting on, Global and Trending only show people you follow and the people they follow.

## Posts

The rules for reading a post are in `core/`:

- `Protocols.kt` finds links, hashtags, mentions, pictures, videos, invoices, polls and threads in a post.
- `Posts.kt` reads picture posts, video posts and articles.
- `Markdown.kt` reads article text.

The screens that draw posts are `ui/Note.kt` (every post card), `ui/Thread.kt` (a post and its replies), `ui/Article.kt`, `ui/Poll.kt`, `ui/Invoice.kt`, `ui/Embed.kt` and `ui/Viewer.kt`.

## Sending without a connection

When a post, reaction, vote or message can't reach any relay for now, `Core.publish` (or `deliver`, for messages) keeps the signed event in the `outbox` table and shows it in the app as if sent. A snackbar says it will go out later.

`Core.sendWaiting` sends what's waiting:
- when the network comes back,
- when the account's session starts,
- every two minutes while the app is open and something is waiting,
- and during each background notification check.

It stops at the first event that still can't go. Events that relays refuse outright are dropped, and so is anything still unsent after three days. The exact same signed event is sent each time, so a relay that got an earlier try keeps only one copy.

## Private messages

These use NIP-17, and the code is in `data/Core.kt` (`deliver`, `sendMessage`, `receiveWrap`) and `ui/Messages.kt`.

**Sending.** A message starts as an unsigned "rumor" (kind 14 for text, 15 for a file, 7 for a reaction). For each person in the chat, the rumor is sealed and gift-wrapped (NIP-59), encrypted with NIP-44, and sent to that person's messaging relays. You get a wrapped copy too, sent to your own messaging relays, so the message shows on your other devices.

**Receiving.** Gift wraps addressed to you are opened once, then stored as plain messages on the phone. Each wrap's id and time are remembered, so it's never opened twice.

**Catching up.** On start, Zino compares the wraps it has from the last few days with each messaging relay (NIP-77), fetches only the missing ones by id, then listens for new ones. Relays without NIP-77 are asked the old way, from a few days before the last message, which downloads some messages again.

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

Zino uses no push service. `data/Notifier.kt` asks Android's JobScheduler to run a short check every so often (15 minutes by default). Each check runs `Core.backgroundCheck`:

1. connect to relays,
2. look for new replies, mentions, reactions, zaps and messages,
3. show notifications,
4. disconnect.

Message notifications never say who sent the message or what it says.

## Media

- **Uploads.** `data/Media.kt` uploads to Blossom servers (NIP-B7). Photos are turned upright and re-encoded first, which removes location and other hidden details.
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
| QR scanning and codes | `ui/Qr.kt` |
| Theme, fonts, colours | `ui/Theme.kt` |
| Shared pieces (avatars, images, rows, times) | `ui/Common.kt`, `ui/Bars.kt` |

## Tests and checks

- **Unit tests** are in `app/src/test/`. They cover the protocol code in `core/` (using the published NIP test vectors where they exist), and the relay pool, WebSocket client and relay stats in `net/`. Tests in `data/` run with Robolectric, which gives them Android's real SQLite on the computer. They cover the database (replaceable events, deletions, counts, messages, the outbox), accounts, opening gift wraps, and keeping posts made without a connection.
- **Device checks** are in `scripts/`. They run by hand from GitHub Actions on an emulator and sign in read-only, so they never publish anything:
  - `smoke.sh` walks through the main screens.
  - `kinds-check.sh` checks how the different post kinds are drawn.
  - `embed-check.sh` checks video embeds.
  - The Negentropy check workflow compares a list of public notes with a real relay (relay.nostr.net by default), to make sure Zino's NIP-77 matches what relays run.
- **`docs-check.py`** runs on every push and fails the build if a file, class or function has no description. See [CONTRIBUTING.md](CONTRIBUTING.md).
