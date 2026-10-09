# VoxelSniperMinestom

[VoxelSniper-Reimagined](https://github.com/KevinDaGame/VoxelSniper-Reimagined) for
[Minestom](https://minestom.net). Brushes, performers, `/b`, `/v`, `/vr`, `/vi` and so on.

Like [WorldEditMinestom](https://github.com/IONNetworkTeam/WorldEditMinestom) it's built for the build
servers of [IONNetwork](https://ion-network.de) and loads as an extension into our Minestom server.
Every snipe goes through the server's edit API (`EditGuard` / `EditTarget`) before anything is
written, so you'll need to provide that API if you want to use it elsewhere.

## What doesn't work

- Biome, entity and sign brushes - the edit API only writes blocks, so these do nothing
- `/u` - the server has its own undo, VoxelSniper's is disabled
- Tree, regenerate chunk and lightning brushes
- Painting, Jockey and Comet brushes write outside the edit API and will fail
- Sneak + left click to pick the replace material isn't wired up yet

Permissions aren't checked by VoxelSniper itself, anyone who is allowed to build on the map can use it.

## Building

You need JDK 25.

```bash
./gradlew build    # build/libs/VoxelSniperMinestom.jar
./gradlew test
```

The build expects `de.ionnetwork:minestom-extension-api` in your local Maven repo. It isn't
published anywhere yet, so for now this only builds if you have it.

## License

LGPL-2.1, same as VoxelSniper-Reimagined. See `LICENSE`.
