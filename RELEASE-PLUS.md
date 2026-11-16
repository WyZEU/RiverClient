# River Plus launch (after 0.3.0, not published)

The client already contains Plus capes, the gold badge, cloth capes, checkout and webhook
delivery. Public access remains hidden until the payment setup and release are ready.

## New Tebex project — configured 2026-11-16

The active project is **River Client**, ID **1928368**, using the Headless API and EUR.
The old project's package IDs and public token must not be reused.

| Package | Tebex ID | Recurring price, including sales tax |
| --- | --- | --- |
| River Plus (1 Month) | `7815389` | €4.99 every month |
| River Plus (3 Months) | `7815393` | €13.99 every 3 months |

Both packages belong to the River Plus category (`3504264`) and have product descriptions
and the River Plus logo. Keep the duration in their English names: the webhook currently
derives the entitlement period from the package name.

- `riverclient.xyz` ownership is verified through its Tebex DNS TXT record.
- Customer support is confirmed as `support@riverclient.xyz`.
- The public token in `cloudflare-updates/wrangler.toml` and the production Worker now
  point to this project. The matching `TEBEX_WEBHOOK_SECRET` is stored as an encrypted
  Cloudflare Worker secret; it must never be committed.
- `https://updates.riverclient.xyz/tebex/webhook` is configured for `payment.completed`,
  `payment.refunded`, `payment.dispute.opened`, `payment.dispute.lost`,
  `recurring-payment.renewed` and `recurring-payment.ended`.
- Tebex's signed validation webhook succeeded. Production `/plus/checkout` requests
  for both durations return checkout links containing the new package IDs, EUR prices
  and the supplied Minecraft UUID.
- The store remains in **test mode**. No real payment or public launch has been performed.

Renewal payloads can carry products under `initial_payment` or `last_payment` directly on
the event subject. The handler now reads those fields and prefers the latest payment,
including after a package change. A three-month renewal grants **94 days**, rather than
falling back to 32. Regression tests also exercise the resulting durable entitlement.

Only the Tebex configuration and handler changes were applied to the live Worker's
existing bundle (version `e8764193`). The Windows updater changes elsewhere in PR #1
have not been deployed by this operation.

## Test checkout — passed 2026-11-16

A three-month checkout completed through Tebex's **Test Payments** simulator, with no
real charge. The displayed €13.99 total included €2.43 sales tax for the Czech checkout.
The return URL reached the site's "You have River Plus" confirmation.

Tests used the synthetic Minecraft UUID `00000000000040008000000000000001`:

| Event | Tebex delivery | Production `/plus/status` after delivery |
| --- | --- | --- |
| `payment.completed` from the checkout | Successful; handler returned `granted` | `plus: true` |
| `recurring-payment.renewed` via Send Test | Successful; handler returned `granted` | `plus: true` |
| `payment.refunded` via Send Test | Successful | `plus: false` |
| Another `recurring-payment.renewed` via Send Test | Successful | `plus: true` |
| `recurring-payment.ended` via Send Test | Successful | `plus: false` |

The actual renewal payload contained both `subject.initial_payment` and
`subject.last_payment`, with the expected three-month product and custom UUID. The
94-day duration is covered by the local regression tests; the public production status
endpoint deliberately exposes only the active flag. Test access was removed by the final
ended event. The store remains in test mode.

## Payout wallet — configured 2026-11-16

The wallet is registered for **Supro Nivelo s.r.o.**, with an EUR bank-transfer payout
method, and is linked to River Client. Tebex confirms the wallet setup succeeded and
shows the saved payout method. Personal verification details and bank account numbers
are not stored in this repository.

The account profile separately reports **Identity Verification: APPROVED**. Tebex
onboarding now shows **100% complete / 0 of 4 steps pending**, and **Submit for Review**
is enabled. The new payout method is subject to Tebex's five-day withdrawal hold.

## Remaining launch steps

1. Submit the project for Tebex review and enable live payments after approval.
2. Turn on `LAUNCHED` in `src/client/kotlin/dev/wyz/clientcore/cosmetic/RiverPlus.kt` and
   `PLUS_LAUNCHED` in `launcher/src/renderer-react/components/river-cosmetics.jsx`.
3. Prepare the 0.3.1 release: update the version and `release-draft.json`, build all 14
   supported Minecraft targets plus the launcher, and complete the release checks.
4. In the separately maintained website, remove the `/plus` password and restore the
   River Plus sentence in the "Is River free?" FAQ
   (`riv3r-next/src/components/HomeSections.tsx` in the website checkout), then deploy.
5. Check the gold badge, Plus capes and cloth cape on a real Minecraft account. Publish
   the client release and announce Plus once the purchase and delivery flow is verified.
