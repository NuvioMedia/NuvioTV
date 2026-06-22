# Changelog

All notable changes introduced in this release for dynamic role-based collections are documented below.

## [feature/dynamic-role-collections]

### Added
- **Dynamic Role-Based Collections**: Added support for creating dynamic collections grouped by roles (e.g. Director, Producer, Writer) directly from Cast and TMDB screens.
- **Custom Naming Flow**: Users can now customize the name of dynamic collections upon creation via a TV-optimized name input dialog.
- **Layout Format Selection**: Added a segmented layout selection dialog, allowing users to choose between **Tabbed Grid** and **Rows** formats when creating collections.
- **Single-Folder Compilation**: Re-routed collection builder to group filmmaker entities under a single unified folder rather than duplicating folders across different roles.
- **Vertical Poster Mode**: Implemented default vertical poster covers (`PosterShape.POSTER`) for filmmaker/person dynamic collections using the filmmaker's profile photo.
- **Production Logo Fallback**: Prioritized high-quality network/company brand logos (`logoUrl`) for dynamic production collections, falling back to open-source Unsplash cinema stills for wide/landscape cards.

### Fixed
- **`crewJob` Preservation**: Fixed a bug where the filmmaker's `crewJob` role property was stripped during web synchronization (added DTO mapping in `CollectionSourceInfo`) and native TV collection edits.
- **Source Key Collisions**: Updated collection source key generation to include the `crewJob` field, preventing duplication and state conflicts when the same filmmaker has multiple roles in a single folder.
