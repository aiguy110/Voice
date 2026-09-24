# Murmur

- Client for a Murmur community server. The wire contract is `docs/protocol.md` in https://github.com/aiguy110/murmur; `MurmurApi` must
  mirror it exactly, and `ManifestTest` must keep the protocol's book-id test vector.
- Keep this module self-contained so it can be offered upstream: Voice-side changes are limited to `Destination.Murmur`, the Settings
  entry, and `app` wiring.
- `MurmurSync` must stay resumable and idempotent; it runs every 15 minutes and whenever the user acts, and can be killed at any point.
