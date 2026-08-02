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
| `/map login` | Sign in as yourself, so pushes are recorded under your name |
| `/map logout` | Stop acting as yourself |
| `/map status` | Who you are signed in as, which map this world is, and which version it was built on |
| `/map link <namespace/name>` | Ties this world to a map, creating the map if it is new |
| `/map push [address] [note]` | Packs the world, uploads it, publishes a new version. The address links a world that has none |
| `/map fork <namespace/name>` | A new map from this one's current version. Copies no bytes |
| `/map versions` | The last ten versions and their state |
| `/map poi set <name>` | Marks where you stand, facing where you look |
| `/map poi list` / `tp <name>` / `remove <name>` | See them, stand in one, drop one |

Deliberately no digests, no version numbers to type, no bucket names. `/map push bedwars/crater`
links a fresh world and pushes it in one go; after that `/map push` alone is enough, and anything
you type becomes the note.

Addresses are lowercase — they become URL paths and object keys, so the registry allows only
`[a-z0-9-]` per segment. Typing a world name is fine: `/map push lobby/MainLobby` becomes
`lobby/mainlobby` and says so, and the readable name survives as the map's display name.

The link lives in `plugins/GroundsMaps/links.yml`, keyed by the world's **UUID** — so renaming a
world in the navigator cannot separate it from its map. It is not stored as BuildSystem world data:
`WorldDataKey.of` is public, but `WorldDataImpl` registers its properties at construction and
rejects anything a third party invents with `Unknown world data key`.

**Publishing is not going live.** A push makes a version that an admin can put in front of players
from the portal. Builders never move a pin — that separation is enforced by the registry, not here.

### Places in a map

A gamemode needs to know where players spawn, where a bed stands, where a generator ticks.
Builders mark those by **standing there**:

```
/map poi set lobby.spawn
/map poi set team.red.bed
/map poi tp team.red.bed
```

The facing comes from where they look — a spawn that drops players into a wall is a bug report
nobody can explain from coordinates alone. Names are lowercase and dotted (`team.red.spawn`) so a
gamemode can ask for a prefix and get a group; what a builder types is normalised into that shape.

**The points live inside the world**, at `grounds/pois.json`, so they travel in the bundle. A
version's places are therefore fixed the moment it is published, and a gamemode reads them from the
map it just loaded rather than from a second store that has to be kept in step with which version is
live. Removing a point changes the next push, never a published version.

The file is written beside itself and moved into place: half a file here is a map with no spawns,
and nobody would find out until a server loaded it.

### Bundles are reproducible

`WorldArchive` writes a `.tar.zst` with sorted entries and fixed mode, mtime, owner and group, so an
unchanged world produces an unchanged digest. The registry addresses bundles by that digest: without
reproducibility every push would store another copy of identical bytes, and "nothing changed" would
be indistinguishable from a real edit.

Player state (`playerdata`, `stats`, `advancements`) and `session.lock` / `uid.dat` are left out. A
map is a place, not the people who visited it while it was built.

## Setting it up

**`GROUNDS_MAPS_CLIENT_SECRET` is optional.** It gives the build server an identity of its own, for
pushes made without anybody signed in. Without it the plugin still enables and logs a note — every
action then has to be somebody's, which is arguably the better way to run it. Set it only if you
want unattended pushes to work.

### Where the secret comes from

It is **not** a value anyone types. `buildserver-maps` is a Keycloak client created by
`grounds-pulumi` (`core/src/platform/buildserver-keycloak.ts`), and Keycloak generates its secret.
The core stack exports it:

```bash
cd grounds-pulumi/core
pulumi stack select grounds-core-upcloud
pulumi stack output buildserverMapsClientSecret --show-secrets
```

If that output is empty, the client has not been applied yet — check that `core:enableKeycloakClients`
is on and that the last core apply was green.

Three things the Pulumi module sets up that the registry checks, so none of them is a manual step:

| What | Why it matters |
|---|---|
| `aud: service-maps` in the token | The registry validates the audience. A token minted for another client is refused even though the realm signed it. |
| a `groups` claim | The registry authorises on group membership; without the mapper the token is valid and can do nothing. |
| the service account in `builder` | `builder` may author and publish. Only `admin` moves a pin — going live stays in the portal. |

The client is a managed Pulumi resource rather than an entry in the realm import, because
re-importing the realm rotates other clients' secrets and Infisical does not track that. The failure
mode is forge returning 401 hours after an unrelated change.

### Setting it: a server you run yourself

The plugin reads the environment, so export it before starting Paper — never put it in
`config.yml`, which builders can read:

```bash
export GROUNDS_MAPS_CLIENT_SECRET='<the value from pulumi stack output>'
java -Xmx4G -jar paper-26.2-87.jar --nogui
```

Shell history keeps that line. Either prefix the export with a space (most shells then skip it) or
keep it in a file only you can read and source it:

```bash
install -m 600 /dev/null ~/.grounds-maps.env
echo "GROUNDS_MAPS_CLIENT_SECRET='<value>'" > ~/.grounds-maps.env
set -a; . ~/.grounds-maps.env; set +a
java -Xmx4G -jar paper-26.2-87.jar --nogui
```

Check it took: the log should say `Map registry: <url>` on enable instead of the error above.

`registry.base-url` defaults to `https://api.grounds.gg`, which works from anywhere — the registry
is served there under `/v1/maps`, alongside forge. There is no in-cluster address to fall back to:
the registry runs on core and the build server does not.

### Setting it: the deployed build server

Stage the same value into Infisical for the cluster the build server runs on, and let the
`InfisicalSecret` render it into a Kubernetes Secret the pod mounts as an environment variable:

```yaml
env:
  - name: GROUNDS_MAPS_CLIENT_SECRET
    valueFrom:
      secretKeyRef:
        name: buildserver-maps
        key: GROUNDS_MAPS_CLIENT_SECRET
```

Do not bake it into an image or a chart value — a chart that carries a secret puts it in the release
history, where it outlives every rotation.

### Rotating it

Delete the client secret in Keycloak (or `pulumi destroy` just that resource) and re-apply; the
module regenerates it. Then re-read the stack output and restage. Nothing caches it beyond the
plugin's process, so a restart is the whole rollout.

### Also needed

The registry has to be reachable from the build server. It runs on core; the build server runs on
the dev spoke, which is exactly why authentication is Keycloak rather than a ServiceAccount token —
a token minted by one cluster does not verify against the other's JWKS.

### Known gap

`/map push` saves the world and switches autosave off while packing, which closes most of the window
in which a tick could write a region file mid-archive. The complete fix is to unload the world, pack
it, and load it back; that is more disruptive to a builder standing in it, and worth doing once
pushes are frequent enough for the risk to be real.
