# River Client 0.3.0 (draft, not published)

## Discord message

```
## <:River_Client:1513187106783236199>  River Client 0.3.0
<t:UNIX:f>

**New River menu look.** Smooth font, calmer cards, and the HUD editor opens a module's
settings right next to it.

**Performance mods by default.** Every River instance gets Sodium, Lithium, FerriteCore and
more once, on its next launch. Remove one and it stays removed.

**Keybinds page.** Every River key in one list in the menu, and you can finally change the
menu key from Right Shift.

**26.3 starts again,** the lyrics crash on 26.3 is fixed, and River gets flagged by way fewer scanners now.

### ❓ Issues go in https://discord.com/channels/1501139838311596103/1541128365879197746
### 📒 Full changelog at https://riverclient.xyz/changelog
@everyone
```

`UNIX` is filled in by the publish script (launcher/dist/River-Client-0.3.0-Discord-Messages.txt).

## Release day order

River Plus is NOT in this release (hidden: RiverPlus.LAUNCHED and PLUS_LAUNCHED in
launcher river-cosmetics.jsx are false). It ships in its own update, see RELEASE-PLUS.md.

1. Approve this draft (launcher/release-draft.json "approved": true).
2. Build all 14 versions + launcher, publish (first signed publish).
3. Test: fresh River instance gets the perf mods, friends sign-in works, no "Get River Plus" anywhere.
4. Post the Discord message, GitHub orphan publish, update the website screenshots.
