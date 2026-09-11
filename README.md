# SmartyCraft Galaxy Compat

Client-side compatibility patches that let a pack assembled from genuine upstream mod releases join a SmartyCraft server that runs rebuilt, relabelled copies of those same mods.

Minecraft 1.12.2, Forge. Plain ASM coremod, no mixin loader required.

## Why it exists

The server's mod set is not the one its authors published. Several jars are upstream releases rebuilt under version numbers that never existed: Ender IO 5.2.61 shipped as `5.2.0`, Quark r1.6-179 as `r1.6-180`, AutoRegLib 1.3-32 as `1.3-33`. A pack that installs the real releases therefore disagrees with the server about what it is running, and some mods refuse the connection over it.

Cloaking the outbound handshake is not enough on its own. Forge's own comparison can be satisfied that way, but a mod is free to register a `@NetworkCheckHandler` and inspect what the *other* side reported, and that check sees the truth whatever the client claimed about itself.

## What it patches

### Ender IO

Ender IO's handler compiles down to

```java
return remote.containsKey("enderio") && "5.2.61".equals(remote.get("enderio"));
```

with its own version inlined as a constant. Against a server answering `5.2.0` the client refuses by itself, before the server's opinion matters at all.

`EnderIOVersionCheckTransformer` replaces the body with the presence half of the original test. The other side must still have Ender IO; only the version equality is dropped. A blind `true` would also accept a server without the mod, which the original never did.

## Not covered

Some divergences are content, not version strings, and no load-time patch can bridge them:

- **IndustrialCraft 2** — the server runs a build with `TileEntityBatchCrafter`, `TileEntityTeleporter` and `assets/ic2/config/macerator.ini` changed. A pack must ship that build.
- **Advanced Solar Panels** — the shipped jar is a recompile of release 4.3.0 against a newer IC2 API (`InvSlotOutput` takes `IInventorySlotHolder` where 4.3.0 passes `TileEntityInventory`). Stock 4.3.0 throws `NoSuchMethodError` when a Molecular Assembler is placed. A call-site rewrite would fit here; it is not written yet.

## Building

Needs a Java 8 JDK (a JRE is not enough: ForgeGradle refuses it).

```
JAVA_HOME=/path/to/jdk8 ./gradlew build -PmodVersion=0.1.0
```

The jar lands in `build/libs`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
