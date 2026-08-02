# This fork

`groundsgg/buildsystem` is a fork of [thomasmny/BuildSystem](https://github.com/thomasmny/BuildSystem),
kept under **GPL-3.0-or-later** like its upstream. It runs the Grounds build server: the place where
builders make maps, and the only place a map version is created.

Internal use is not distribution, so nothing here has to be published — but every file keeps its
license header, and anything we hand to a third party carries the GPL with it.

## What we changed, and why it is a short list

The fork exists to be updated, so upstream's code is touched as little as possible.

| Change | Where | Why |
|---|---|---|
| Paper instead of Spigot | `buildsystem-core/build.gradle.kts` | We run Paper. Upstream compiles spigot-only on purpose; that constraint is not ours. |
| `paper-api` pinned to the exact server build | `gradle/libs.versions.toml` | What we compile against and what we deploy onto should be one number, not two moving targets. |
| **`buildsystem-grounds`** | new module | Everything Grounds-specific. It depends on `buildsystem-api` only — never on `buildsystem-core`. |

That last rule is the important one. Because our code lives in its own module and speaks only to the
published API, an upstream merge cannot conflict with it, and it cannot hold an upstream update back.

## Merging upstream

```bash
git fetch upstream
git merge upstream/main
```

Conflicts should be confined to the three rows in the table above. If a merge starts touching
`buildsystem-grounds`, something has been wired the wrong way round.

## The Grounds module

One plugin, `GroundsMaps`, depending on `BuildSystem`. It gives builders one command:

| Command | What it does |
|---|---|
| `/map status` | Which map this world is, and which version it was built on |
| `/map link <namespace/name>` | Ties this world to a map, creating the map if it is new |
| `/map push [note]` | Packs the world, uploads it, publishes a new version |
| `/map fork <namespace/name>` | A new map from this one's current version. Copies no bytes |
| `/map versions` | The last ten versions and their state |

Deliberately no digests, no version numbers to type, no bucket names: `/map push` works out which map
the world belongs to from the world itself. The link is stored as BuildSystem world data, so renaming
a world through the navigator cannot separate it from its map.

**Publishing is not going live.** A push makes a version that an admin can put in front of players
from the portal. Builders never move a pin — that separation is enforced by the registry, not here.

### Bundles are reproducible

`WorldArchive` writes a `.tar.zst` with sorted entries and fixed mode, mtime, owner and group, so an
unchanged world produces an unchanged digest. The registry addresses bundles by that digest: without
reproducibility every push would store another copy of identical bytes, and "nothing changed" would
be indistinguishable from a real edit.

Player state (`playerdata`, `stats`, `advancements`) and `session.lock` / `uid.dat` are left out. A
map is a place, not the people who visited it while it was built.

### Configuration

`plugins/GroundsMaps/config.yml` holds the registry URL and the OIDC client id. The client **secret**
comes from `GROUNDS_MAPS_CLIENT_SECRET` in the environment and never from a file a builder can read.
The plugin refuses to enable without it, because a build server that silently cannot publish looks
like a working build server until somebody finishes a map.

### Still needed before this runs

- A Keycloak client `buildserver-maps` with a service account, whose tokens carry `aud: service-maps`,
  in a group the registry grants authoring and publishing to (`builder`).
- The registry reachable from the build server. It runs on core; the build server runs on the dev
  spoke, which is exactly why authentication is Keycloak rather than a ServiceAccount token — a
  token minted by one cluster does not verify against the other's JWKS.

### Known gap

`/map push` saves the world and switches autosave off while packing, which closes most of the window
in which a tick could write a region file mid-archive. The complete fix is to unload the world, pack
it, and load it back; that is more disruptive to a builder standing in it, and worth doing once
pushes are frequent enough for the risk to be real.
