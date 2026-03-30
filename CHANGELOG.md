# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - 2026-03-30

First public release.

### Added

- Side-by-side XML editors with open file, paste, and drag-and-drop loading.
- Structural diff as an expandable tree table: node label, left value, right value, and status.
- Color legend and filters by change type (match, changed, left-only, right-only) plus text search.
- Column sorting, expand/collapse all, debounced re-compare while typing, and **Compare now**.
- Export filtered tree to TSV.
- Context menu and **Ctrl+C**: copy comparison as TSV; copy full XML subtrees from left or right document for selected rows.
- Status bar with aggregate diff counts after a successful compare.
- Application window icon and dark-themed UI (see `styles.css`).

### Packaging

- Windows portable app via **`mvn -Pdist`** / **`.\scripts\run.ps1 -BuildExe`** (`XmlCompare.exe` with bundled runtime).
- GitHub Actions workflow to attach a versioned **`XmlCompare-Windows-*.zip`** to releases when pushing a **`v*`** tag.

### Technical

- Java 21, JavaFX 21, Maven build.
- UI helpers: `DiffFormat`, `DiffTreeTableSupport`, `DiffTreeClipboardSupport`.

[1.0.1]: https://github.com/laguileraab/ProfilerComparison/releases/tag/v1.0.1
