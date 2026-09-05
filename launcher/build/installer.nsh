; Custom NSIS include for the River Client installer.
;
; Overrides the installer branding footer. electron-builder defaults it to
; "${productName} ${version}", which shows the semver base (0.1.4) - not the real
; River version (e.g. 0.1.4.2), because electron-builder rejects 4-segment versions
; and the real version lives in package.json riverVersion instead. Rather than inject
; riverVersion into NSIS (fragile), drop the version from the footer entirely and show
; a static brand line, so the installer can never display a wrong/stale version.
!macro customHeader
  BrandingText "River Client - riverclient.xyz"
!macroend
