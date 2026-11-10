<img src="docs/icon.svg" width="128" height="128" alt="UrsaNotes icon: a black bear head holding a black pencil in its mouth, on amber">

# UrsaNotes

A small, 'bear minimum' notebook for Android 8+: markdown notes and black-and-white sketches, each in folders you make, all as plain files in `Documents/UrsaNotes`. No libraries beyond the Kotlin standard library in the app itself; it is UrsaPDF's sibling and looks and moves the same.

- Home: two tabs, **Notes** and **Sketches**; tap one or swipe sideways (past either end the swipe goes round to the other). Each holds its own folders, shown as cards; notes and folders carry a colour strip on the edge, sketches none. A note shows its first two lines and when it was last opened; a folder shows how many items and folders it holds. A search (names, and a note's text, through the folders below too) stays pinned at the top; a soft shadow shows where the list runs under it or off the screen. The app's version sits beside its name.
- Each card's ⋮ menu pins it (a pinned item stays at the top), renames it, moves or copies it, shares it or deletes it. **Move/Copy** shows the folder tree from Root; pick a folder and **Create New Folder Here** opens under it, to name a new folder in place (Enter keeps it, Back drops it).
- The sort icon picks Name, Date Accessed, Date Added or Size, ascending or descending; Date Accessed, newest first, is the default. The ⋮ menu holds **Dark Mode** (on at first start, except on e-ink devices; your choice is kept), **Folders First** (on by default), **Export Notes**, **Import Notes** and **Check for Updates** (updates also install themselves from the latest release here).
- A row of three buttons along the bottom makes, from right to left, a **New Note**, a **New Sketch** and a **New Folder** (in the tab on screen). Inside a folder, an up button opens at the row's left, Back does the same, and a path strip under the title bar jumps to any folder above. A long path shows Root, "…" and the nearest folders; tapping "…" shows it all, to drag sideways. The square at the strip's end sets the folder's colour, one of the editor's sixteen.
- Editor: Yeetlist's. Body text, six heading levels and code blocks from a dropdown; bold, italic and lists on the bar; strikethrough, code and links under the dots button. "- ", "1. " and "[ ] " start lists and task lists. A note with no name takes its first line when closed. The colour square picks one of sixteen strip colours, pinch or double-tap and drag or the dropdown sets the text size from 50% to 500%, and search marks every match in amber.
- Sketches: a canvas twice the screen's size, black on white, with one chalk pastel brush in sizes 1 to 5. A stylus's pressure sets the width, from thinner than a finger's line when light to thicker when pressed hard; with a finger, a fast stroke draws thinner. Pinch zooms from 50% (the whole canvas on a phone of the same size) to 200%. The bar holds Return, **Undo** and **Redo** (back to when the sketch was opened), **Wipe**, the brush size, **Invert Canvas** (a new sketch starts the way the last sketch you opened or inverted was left) and **Delete**. Every stroke saves the PNG; a new sketch left blank is never saved.
- Share a note as MD, HTML or TXT, or a sketch as PNG or JPG (each saved to Downloads too), copy a note to the clipboard, or send either to another app.
- Backup: **Export Notes** packs every note, sketch and folder into one zip in Downloads and opens the share sheet; **Import Notes** reads one back, and an item whose name is taken arrives beside it as "Name (2)".
- Storage: Android 11 and newer ask for All files access to keep everything in `Documents/UrsaNotes`, in `Notes` and `Sketches`. Until it is allowed, it stays in the app's own folder and moves over the first time it is. Colours, pins and dates sit in `.ursatxt.json` in each.

## Build

```
./gradlew assembleRelease
```

Release signing reads `URSA_KS_PATH` and `URSA_KS_PASS` from the environment or from `keystore.properties` (not in git).

## Release

Push a tag `vX.Y.Z`. The workflow builds a signed APK, attaches it to a GitHub release and deletes every older release, so only the newest exists.

## Credits

- **[Tabler Icons](https://tabler.io/icons)** by Paweł Kuna, MIT licence: every icon in the app. Converted to Android vectors with `tools/svg2vd.py`.

Full licence texts are in `THIRD_PARTY_NOTICES.md`.
