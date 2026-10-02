# Signing continuity

The app keeps its existing application ID. Updating an installed APK requires
the signing certificate to match. A public APK contains the certificate, not
the private key; extracting its certificate does not recover the signing key.

Configure GitHub Actions repository Secrets (do not commit a keystore):

- `CAPTION_SIGNING_KEYSTORE_BASE64`: base64 of the recoverable existing keystore.
- `CAPTION_KEYSTORE_PASSWORD`, `CAPTION_KEY_ALIAS`, `CAPTION_KEY_PASSWORD`:
  its actual credentials; defaults remain `android` / `androiddebugkey` / `android`
  for compatibility with the earlier optional debug-key hook.

After the key is configured, set Actions repository variable
`CAPTION_REQUIRE_PERSISTENT_SIGNING=true`. This makes CI fail instead of silently
creating a different certificate. CI restores the private file only to runner
temporary storage and removes it in cleanup. `signing-info.txt` records only the
public certificate fingerprint, so compare successive APK fingerprints.

This implementation does **not** mean the Secrets have been set. They could not
be administered with the currently available repository connector. No private
key was generated, printed, or committed in this change. Until a valid key is
provided, CI labels builds as temporary test certificates. If the private key
for the installed v1.0.0 APK cannot be recovered, matching its signature is not
possible; a one-time migration/reinstall is necessary before future updates can
use a newly retained key. Keep the key backed up independently of CI.
