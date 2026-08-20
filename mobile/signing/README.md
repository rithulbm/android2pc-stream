# CI development signing

`ci-development.p12.b64` is an intentionally public, development-only Android signing identity used by GitHub Actions to make the repository-root APK installable and consistently updatable across CI builds.

It is **not a secret and must never be used for Google Play, production distribution, privileged signing, or any build that relies on signer identity for trust**. The private key is public by design.

- Alias: `local-camera-development`
- Store/key password: `development-only`
- Certificate SHA-256: `FD:06:21:D9:B8:63:89:F2:59:DE:37:7D:EE:27:AF:B4:56:47:7F:DB:D2:0B:9C:57:69:7A:C5:D3:6E:7C:4B:E7`
- Subject: `CN=Local Camera CI Development, OU=Development Only, O=Local Camera Receiver, C=IN`

For a production release, replace the CI signing step with a private signing identity supplied through protected GitHub Actions secrets and keep that key out of the repository.
