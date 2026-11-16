# Contributing to Zino

Zino aims to stay small, fast and easy to read. These rules apply to every change.

## Keep the code described

Descriptions are part of the change itself, not something to add later.

1. **Every Kotlin file starts with a `/* … */` paragraph** before `package`. It says, in plain words, what the file is for and anything surprising about it. New files get one. When you change a file, re-read its paragraph and fix anything your change made wrong.
2. **Every class, object, interface and function has a comment right above it**: one `/** … */` line saying what it is for, or `//` for a tiny private helper. Write "True if…" for checks and "A…" or "The…" for things. Name the NIP when one applies.
3. **Comments say why, not what.** Explain relay quirks, Android rules and choices between alternatives. Don't narrate each line.
4. **Describe only what's there.** Check each claim against the code before writing it.
5. **Update [ARCHITECTURE.md](ARCHITECTURE.md) in the same change** when you add, remove, rename or move a feature, screen, storage table or file, or change how the parts talk to each other.
6. **Update [README.md](README.md)** for anything about features, supported NIPs, building, permissions or signing.

`python3 scripts/docs-check.py` lists anything left undescribed. CI runs it on every push, and the build fails until it passes.

## Text

- **Every word the app shows goes in `app/src/main/res/values/strings.xml`.** Read it in code with `str(R.string.name)`, or `plural(R.plurals.name, count, …)` for counts. Don't write text into Kotlin.
- **Use whole sentences with placeholders**, like `Replying to %1$s`, instead of joining pieces, so translators can reorder words.
- **Translations** go in a copy of the file under `values-xx` (for example `values-de` or `values-pt-rBR`). Keep every name and every `%1$s`.

## Protocol

- Implement only current, official NIPs, in their current form. Write no deprecated formats.
- Prefer code in this repository over new libraries. The only libraries beyond Compose are libsecp256k1 and the ZXing core.

## Testing

- Unit tests are in `app/src/test/`. Run them with `./gradlew testDebugUnitTest`.
- The device checks in `scripts/` (smoke, kinds, embed) run by hand from Actions. They sign in read-only with a public npub and must never publish to relays.

## Commits and releases

- Write a short summary line, then a few lines on what changed and why.
- Bump the minor version for new features and the patch version for fixes.
- A release is published by pushing `release-notes/vX.Y.Z.md` to `main` in a commit named "Release vX.Y.Z". The notes are plain language, written for people who use the app.
- Never commit keystores, passwords or other secrets.
