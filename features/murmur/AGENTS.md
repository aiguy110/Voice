# Murmur

- Client for a Murmur community server. The wire contract is `docs/protocol.md` in https://github.com/aiguy110/murmur; `MurmurApi` must
  mirror it exactly, and `ManifestTest` must keep the protocol's book-id test vector.
- Keep this module self-contained so it can be offered upstream: Voice-side changes are limited to `Destination.Murmur`, the Settings
  entry, and `app` wiring.
- `MurmurCommunityLibrary` implements the `CommunityLibrary` seam (`core/data/api`, `voice.core.data.community`), so Murmur books show in Voice's library under
  "Murmur Community". `MurmurRepository.library` is the cached library; every sync and user action refreshes it and `MurmurCovers`
  (holders upload a 400px thumbnail when the server has none; everyone else caches covers under `filesDir/murmur/covers`).
- `MurmurUpdater` checks the fork's latest GitHub release on every sync run (tags `murmur-<versionCode>`) and installs via
  PackageInstaller. It only acts when the package name ends in `.murmur`.
- `MurmurSync` must stay resumable and idempotent; it runs every 15 minutes and whenever the user acts, and can be killed at any point.
- `SabpImport` copies progress from Smart AudioBook Player's per-folder `position.sabp.dat` (Java-serialized; `SabpStateTest` uses real
  files). It only touches books Voice shows as not started, so it's safe to rerun.
