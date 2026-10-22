# Ars Planarium Patch

A mixin-based addon for **Ars Nouveau 5.13.3** (Minecraft 1.21.1 / NeoForge) that stores every
Planarium in **one shared dimension** instead of creating a separate dimension per Planarium.

## Why

Ars Nouveau creates a full `ServerLevel` (with its own region files, chunk generator and ticking
machinery) for every named Planarium. A server with dozens of Planariums ends up with dozens of
dimensions, which is expensive and awkward to manage.

This patch keeps all Planariums in a single dimension, `ars_planarium_patch:planariums`, placing each
Planarium in its own cell of an infinite grid. Cell coordinates are derived from a hash of the
Planarium name, so they are stable and need no central registry.

## How it works

| Piece | Purpose |
|---|---|
| `PlanariumGridChunkGenerator` | Builds the 34x33x34 boundary box in every cell, deterministically, from the chunk position. |
| `PlanariumLayoutData` (overworld `SavedData`) | Persistent `name -> id -> cell` mapping, per-cell spawn, migration state, stored grid stride. |
| `PlanariumTileMixin` | Replaces dimension creation (`setDimension`) and teleport (`sendEntityTo`). |
| `PlanariumDimManagerMixin` | Loads templates from cells, routes block place/break events to the right cell. |
| `PlanariumService` | Allocation/migration plus the event-driven miniature refresh. |
| `DimBoundaryMixin` | Resolves the Dominion Wand spawn point within the clicked cell. |
| `LegacyDimensionCleanup` | Unregisters old per-Planarium dimensions after their contents were migrated. |

Rendering, networking and the client are untouched: templates are still snapshotted and rendered in
local 0..31 coordinates.

### Miniature refresh

The miniature is **not** refreshed on a timer. Refreshes are queued by events:

- a player places or breaks a block inside a cell — coalesced with a debounce
  (`refreshDebounceTicks`, default 100 ticks / 5 s after the last change);
- a player leaves the Planarium through its boundary — refreshed immediately;
- a client (re)loads a Planarium and its cached template is missing/stale — the server answers with
  a freshly loaded snapshot.

An idle Planarium costs one map lookup per tick and never force-loads its cell chunks. Snapshots are
only taken when one of the events above actually needs one.

### Cell layout

```
cell base = cellIndex * stride   (stride default 512, configurable, world-stored)
boundary box: base+0 .. base+33, y 0..32
interior:     base+1 .. base+32, y 1..30
template:     base+1 .. base+32, y 1..31
```

Cells never overlap: the stride must be a multiple of 16 and at least 64. The value is written into
the world the first time a Planarium is created; changing the config later logs a warning and keeps
the stored value (changing it would move existing cells).

### Migration

On first access to a pre-patch Planarium, its 32x31x32 contents are copied from the old dimension
into its new cell (including block entity NBT), its old spawn point is preserved, and the old
dimension is unregistered once no player is inside it.

## Dependencies

- Required: Ars Nouveau 5.13.3+
- Optional: ars-sable (tested with 1.2.0 / Sable 2.0.6) — the patch keeps the method signatures and
  calls that ars-sable wraps.

## Building

```
gradlew build
```

## Config

`config/ars_planarium_patch-server.toml`:

```toml
[planarium]
cellStride = 512
refreshDebounceTicks = 100
```

`refreshDebounceTicks` is the number of ticks to wait after the last block change before refreshing
a miniature (100 ticks = 5 s, 0 = refresh on every change). Leaving the Planarium always refreshes
immediately.
