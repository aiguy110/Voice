# Murmur

- Client for a Murmur community server. The wire contract is `docs/protocol.md` in https://github.com/aiguy110/murmur; `MurmurApi` must
  mirror it exactly, and `ManifestTest`/`BookTorrentTest` must keep the protocol's book-id and book-torrent test vectors.
- Keep this module self-contained so it can be offered upstream: Voice-side changes are limited to `Destination.Murmur`, the Settings
  entry, and `app` wiring.
- `MurmurTelemetry` implements `FolderPickListener`: when the user opts in, every folder they pick to add is walked and its tree sent to `POST /telemetry`.
- `MurmurCommunityLibrary` implements the `CommunityLibrary` seam (`core/data/api`, `voice.core.data.community`), so Murmur books show in Voice's library under
  "Murmur Community". `MurmurRepository.library` is the cached library; every sync and user action refreshes it and `MurmurCovers`
  (holders upload a 400px thumbnail when the server has none; everyone else caches covers under `filesDir/murmur/covers`).
- `MurmurUpdater` checks the fork's latest GitHub release on every sync run (tags `murmur-<versionCode>`) and installs via
  PackageInstaller. The APK is cached under `cacheDir/murmur-update` first, so a declined install prompt can be retried without
  downloading again. It only acts when the package name ends in `.murmur`.
- Books move over BitTorrent (libtorrent4j, `TorrentEngine`, port 42070). `MurmurSync` (every 15 minutes and whenever the user acts)
  refreshes the library, re-shares books the server has no torrent for, and starts `TorrentWorker` (foreground, `dataSync`) when
  `MurmurTorrents.hasWork()`. `MurmurTorrents` plans the session every minute: requested books download into
  `<download folder>/.murmur/<book id>` (Voice's scanner skips dot folders), are checked against the book id, moved into place, and shared
  (or the want withdrawn); `/me/requests` books seed now; on Wi-Fi (and charging, by default) every shared book seeds, as does each
  imported book's public torrent. A wanted book nobody holds and the server hasn't cached comes from its public source instead.
  Everything must stay resumable and idempotent: the worker can be killed at any point.
- Torrents need file paths: `StorageAccess` maps Storage Access Framework uris on device storage to paths, which needs all files
  access (`MANAGE_EXTERNAL_STORAGE`; the storage permission on Android 9-10). Seeding uses libtorrent's seed mode with `renamed_files`
  pointing at the books where Voice has them, so nothing is copied or re-hashed.
- `SabpImport` copies progress from Smart AudioBook Player's per-folder `position.sabp.dat` (Java-serialized; `SabpStateTest` uses real
  files). It only touches books Voice shows as not started, so it's safe to rerun.
- Magnet imports: `MagnetImports` looks a link up (`TorrentEngine.metadata`, libtorrent4j), runs `detectBooks` (core/data/api) on the torrent's
  file list, and lets the user pick books. `MurmurTorrents` drives `MagnetImports.advance`, which downloads only the picked files into
  `<download folder>/.murmur/<info hash>/<book>` and moves each finished book into place. `MurmurSettings.imported` remembers each
  book's magnet and torrent-index-to-path mapping (the torrent file stays in `filesDir/murmur/torrents`), for public seeding and for the
  `source` sent when the user shares it. Imports never touch the Murmur server otherwise. Pending imports live in
  `MurmurSettings.imports` and resume on the next sync. The lookup dialog's state lives in `MagnetImports` because `MagnetLinkActivity`
  (the `magnet:` intent filter) restarts the app on the Murmur screen via `Destination.Murmur.OPEN_ACTION`.
