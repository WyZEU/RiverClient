# River Plus update (after 0.3.0, not published)

Everything for Plus is already in the code (capes, gold badge, cloth cape, checkout, webhook).
It is only hidden. To launch:

1. Dad's Tebex payout wallet is set up, store is out of test mode.
2. Turn it on: `LAUNCHED = true` in src/client/kotlin/dev/wyz/clientcore/cosmetic/RiverPlus.kt
   and `PLUS_LAUNCHED = true` in launcher/src/renderer-react/components/river-cosmetics.jsx.
3. Bump the version (0.3.1), add "New: River Plus. A gold badge, four animated Plus capes,
   cloth capes and early access. Everything else stays free." to release-draft.json.
4. Build all 14 + launcher, publish.
5. Site: remove the /plus password, put the River Plus sentence back in the "Is River free?" FAQ
   (riv3r-next/src/components/HomeSections.tsx, see site commit before this one), deploy.
6. Test on WyZ's real account: buy or grant Plus, check the gold badge, Plus capes, cloth cape.
7. Discord message leading with River Plus, GitHub orphan publish.
