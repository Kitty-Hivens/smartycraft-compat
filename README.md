# SmartyCraft Galaxy Compat

Client-side compatibility patches that let a pack assembled from genuine upstream mod releases join a SmartyCraft server that runs rebuilt, relabelled copies of those same mods.

Minecraft 1.12.2, Forge. Plain ASM coremod, no mixin loader required.

## Why it exists

The server's mod set is not the one its authors published. Several jars are upstream releases rebuilt under version numbers that never existed: Ender IO 5.2.61 shipped as `5.2.0`, Quark r1.6-179 as `r1.6-180`, AutoRegLib 1.3-32 as `1.3-33`. A pack that installs the real releases therefore disagrees with the server about what it is running, and some mods refuse the connection over it.

Cloaking the outbound handshake is not enough on its own. Forge's own comparison can be satisfied that way, but a mod is free to register a `@NetworkCheckHandler` and inspect what the *other* side reported, and that check sees the truth whatever the client claimed about itself.

Other jars are rebuilt to change behaviour rather than identity. Those cannot be handled at the handshake at all: the genuine release simply behaves differently from the one the server runs. Where the difference is reachable from the client, it is ported here.

## What it patches

### Ender IO

Ender IO's handler compiles down to

```java
return remote.containsKey("enderio") && "5.2.61".equals(remote.get("enderio"));
```

with its own version inlined as a constant. Against a server answering `5.2.0` the client refuses by itself, before the server's opinion matters at all.

`EnderIOVersionCheckTransformer` replaces the body with the presence half of the original test. The other side must still have Ender IO, only the version equality is dropped. A blind `true` would also accept a server without the mod, which the original never did.

### Advanced Solar Panels

The shipped jar is a recompile of release 4.3.0 against a newer IndustrialCraft 2 API. Its inventory slots are constructed from `IInventorySlotHolder` where 4.3.0 passes `TileEntityInventory`, and the old overloads are gone, so stock 4.3.0 throws `NoSuchMethodError` the moment a Molecular Assembler is built.

`AdvancedSolarSlotApiTransformer` rewrites those three constructor descriptors. `TileEntityInventory` implements `IInventorySlotHolder`, so the value already on the stack satisfies the new parameter and nothing about the mod's behaviour moves.

### IndustrialCraft 2

The server's IC2 is a genuine release with two files changed plus one config: `TileEntityBatchCrafter`, `TileEntityTeleporter` and `assets/ic2/config/macerator.ini`. Galaxy is built on 2.8.221 and Industrial on 2.8.222, but the patched members are byte-identical in both, so one port covers both packs.

Most of it never reaches a client:

- `TileEntityTeleporter` changes the arrival height from 1.5 to 2.0 above the target block. `teleport` is called only from `updateEntityServer`, so the server does the move and sends the client a position.
- `TileEntityBatchCrafter.canCraft` gains a second pass comparing the cached crafting matrix against the ingredient inventory, and `doCrafting` revalidates with `canCraft` instead of `hasRecipe` between operations. Both are reached only from `updateEntityServer`.

What does reach a client is the ingredient slot filter, and the recipe config.

`BatchCrafterSlotFilterTransformer` handles the filter. The anonymous `InvSlot` for each ingredient slot tries the candidate stack in the cached matrix and asks the cached recipe whether it still matches. The server narrows that to the item the matrix already holds:

```java
return recipe.matches(crafting, world)
    && StackUtil.checkItemEqualityStrict(stack, old);
```

closing the gap between "any stack this recipe would accept here" and "the stack this cached operation was set up for". `accepts` branches on `world.isRemote` to pick its recipe, so the client evaluates it for slot validity in the open GUI, and without the patch the client offers slots the server will reject.

The recipe config is not code and is not carried by this mod. IC2 reads `config/ic2/<name>.ini` from the instance directory and falls back to the jar asset only when that file is absent or unreadable, so the server's recipe set transfers as a plain file. [`pack/config/ic2/macerator.ini`](pack/config/ic2/macerator.ini) is that file, copied verbatim out of the server's jar, and belongs in the pack as an asset rather than in this jar. It differs from upstream in four ways:

- plates macerate to eight small dust instead of one full dust, for iron, copper, tin, gold, lead, bronze, obsidian and lapis
- dense plates yield eight dust instead of nine, and the commented out dense steel entry is live, mapping to iron dust
- crushed ores, purified crushed ores, netherrack, ender pearls, ender eyes and emeralds no longer macerate at all
- iridium is keyed on `ic2:misc_resource#iridium_ore` rather than the `gemIridium` ore dictionary entry, and Applied Energistics certus quartz, nether quartz and fluix crystals are added

## Building

Needs a Java 8 JDK (a JRE is not enough: ForgeGradle refuses it).

```
JAVA_HOME=/path/to/jdk8 ./gradlew build -PmodVersion=0.2.0
```

The jar lands in `build/libs`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
