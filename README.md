# SmartyCraft Compat

Client-side compatibility patches that let a pack assembled from genuine upstream mod releases run against a SmartyCraft server, where several of the mods are rebuilt, relabelled copies of the published ones.

Minecraft 1.12.2, Forge. Plain ASM coremod, no mixin loader required.

Covers every SmartyCraft pack rather than one of them. The packs overlap heavily, and where two of them ship the same rebuilt mod they ship the same bytes, so a patch written once applies to all of them.

## Why it exists

The server's mod set is not the one its authors published. Several jars are upstream releases rebuilt under version numbers that never existed: Ender IO 5.2.61 shipped as `5.2.0`, Quark r1.6-179 as `r1.6-180`, AutoRegLib 1.3-32 as `1.3-33`. A pack that installs the real releases therefore disagrees with the server about what it is running, and some mods refuse the connection over it.

Cloaking the outbound handshake is not enough on its own. Forge's own comparison can be satisfied that way, but a mod is free to register a `@NetworkCheckHandler` and inspect what the *other* side reported, and that check sees the truth whatever the client claimed about itself.

Other jars are rebuilt to change behaviour rather than identity. Those cannot be handled at the handshake at all: the genuine release simply behaves differently from the one the server runs. Where the difference is reachable from the client, it is ported here.

A third kind has nothing to do with the server. A mod written against an API the JVM no longer has needs that call redirected somewhere correct, and the redirect the platform supplies is not always right.

## What it patches

### Ender IO

Ender IO's handler compiles down to

```java
return remote.keySet().contains("enderio") && "5.2.61".equals(remote.get("enderio"));
```

with its own version inlined as a constant. Against a server answering `5.2.0` the client refuses by itself, before the server's opinion matters at all.

Ender IO ships as eleven mod ids, and ten of its classes carry a handler of their own, each asking after the module it belongs to: the base mod, the conduits, the three conduit integrations, the two Tinkers integrations, the Forestry integration, the machines and the power tools. Patching only the first leaves nine others to refuse the connection.

`EnderIOVersionCheckTransformer` rewrites every class in Ender IO's package tree that declares the method, and reads the mod id each handler demands out of its own body rather than assuming they all ask after the base mod. The body becomes the presence half of the original test: the other side must still have that module, only the version equality is dropped. A blind `true` would also accept a server without it, which the original never did.

### Advanced Solar Panels

The shipped jar is a recompile of release 4.3.0 against a newer IndustrialCraft 2 API. Its inventory slots are constructed from `IInventorySlotHolder` where 4.3.0 passes `TileEntityInventory`, and the old overloads are gone, so stock 4.3.0 throws `NoSuchMethodError` the moment a Molecular Assembler is built.

`AdvancedSolarSlotApiTransformer` rewrites those three constructor descriptors. `TileEntityInventory` implements `IInventorySlotHolder`, so the value already on the stack satisfies the new parameter and nothing about the mod's behaviour moves.

That is the whole difference. Comparing the two jars method by method turns up nothing else: the remaining apparent changes are synthetic bridge methods that a decompile and recompile round trip rebuilt in a different shape, and the bodies they forward to are identical.

### IndustrialCraft 2

The server's IC2 is a genuine release with two classes changed plus one recipe config: `TileEntityBatchCrafter`, `TileEntityTeleporter` and `assets/ic2/config/macerator.ini`. Galaxy is built on 2.8.221 and Industrial on 2.8.222, and the patched members are byte-identical in both.

Most of it never reaches a client:

- `TileEntityTeleporter` changes the arrival height from 1.5 to 2.0 above the target block. `teleport` is called only from `updateEntityServer`, so the server does the move and sends the client a position.
- `TileEntityBatchCrafter.canCraft` gains a second pass comparing the cached crafting matrix against the ingredient inventory, and `doCrafting` revalidates with `canCraft` instead of `hasRecipe` between operations. Both are reached only from `updateEntityServer`.

What does reach a client is the ingredient slot filter. `BatchCrafterSlotFilterTransformer` handles it. The anonymous `InvSlot` for each ingredient slot tries the candidate stack in the cached matrix and asks the cached recipe whether it still matches. The server narrows that to the item the matrix already holds:

```java
return recipe.matches(crafting, world)
    && StackUtil.checkItemEqualityStrict(stack, old);
```

closing the gap between "any stack this recipe would accept here" and "the stack this cached operation was set up for". `accepts` branches on `world.isRemote` to pick its recipe, so the client evaluates it for slot validity in the open GUI, and without the patch the client offers slots the server will reject.

### AE2 Stuff

The published release asks BdLib whether two stacks are the same item, and BdLib finishes with `ItemStack.areItemStackTagsEqual`, which wants equal NBT *and* compatible capabilities. The server's copy compares NBT alone, so it accepts pairs the published one turns away.

That reaches a client. The inscriber's `isItemValidForSlot` runs through `isValidPartialRecipe` to this comparison, and slot validity is evaluated on both sides, so an unpatched client refuses ingredients the server would have taken.

`InscriberMatchTransformer` swaps one instruction at each of the seven call sites inside the inscriber's package: the `invokevirtual` on BdLib's singleton becomes an `invokestatic` on our own comparison, which takes that singleton as an ignored leading parameter so the operand stack is left exactly as it was. The comparison itself is ordinary Java, checked against the server's branch structure over every combination of item, subtype flag, damage and tag.

BdLib's comparison is used all over AE2 Stuff and by other mods that ship BdLib. Only the inscriber's use of it differs on the server, so only that package is touched.

### Thermal Expansion

The Cache hands a stack back when a player interacts with it, and the published release always writes that stack into the selected hotbar slot, whichever hand did the interacting. Reach for the Cache with the off hand and the main hand's item is what gets replaced. The server's copy branches on the hand and writes the off hand slot when that is the one in use.

`BlockTEBase.onBlockActivated` reaches the delegate without a side check, so an unpatched client predicts the wrong slot and shows the swap in the wrong place until the server's next window update corrects it.

`CacheOffHandTransformer` pushes the hand, which the method already holds in local 5, and turns the inventory write into a call that takes it. The branch itself is ordinary Java. One extra operand, no new jump targets, nothing to recompute.

### Twilight Forest

The goblin uncrafting table's result slot clears the uncrafting matrix and charges the experience cost, then hands off to the vanilla crafting slot. The published release stops there. The server's copy halves whatever each assembly slot still holds once that has returned, so the same ingredients cannot be drawn twice.

It does that only on the uncrafting path. A result equal to what a plain recipe would produce is ordinary crafting and is left alone.

Container slots are evaluated on both sides, so without this the client predicts an assembly matrix the server does not agree with.

`UncraftingTakeTransformer` appends to the tail call rather than rebuilding the method. The published release reaches its single `super.onTake` from both paths, so the flag the method has already computed is pushed alongside the result and the matrix, and the decision is made in ordinary Java. Three pushes and one call, no new jump targets. The flag is located rather than assumed: it is the local feeding the one `IFEQ` a plain load reaches.

### Thaumcraft

The hand mirror container takes whatever sits in the selected hotbar slot and calls it the mirror. Open the mirror from the off hand and the container is built around the main hand's item, so the client shows a container that has nothing to do with what the player used. The server's copy looks in the off hand when the main hand is not holding one.

`HandMirrorOffHandTransformer` inserts straight after the constructor stores what it read from the selected slot: the field, the inventory and the mod's own mirror class go on the stack and the decision is made in ordinary Java. Nothing here is named by a mapping. The store is found by following the only no-argument `InventoryPlayer` call returning an `ItemStack`, the field by the store it feeds, and the mirror class by a call the class already makes into it.

One half is deliberately left out. The server also nulls the field when neither hand holds a mirror, and closes the screen on that null elsewhere. The published release's other methods have never had to expect a null there, so carrying it would trade a cosmetic mismatch for a crash. A miss answers with the empty stack instead, which is what the release already puts there for an empty hand.

### Railcraft

Railcraft asks who called it so it can register a `DataParameter` against that entity class:

```java
Class<?> owner = sun.reflect.Reflection.getCallerClass(2);
return EntityDataManager.createKey(owner.asSubclass(Entity.class), serializer);
```

Depth 2 is correct for the method it was written against. That method is gone from modern JVMs, so Cleanroom's Fugue redirects the call, swapping the owner and keeping the name and descriptor. Its target counts one frame further out, so Railcraft receives the class that loaded the entity rather than the entity, and `asSubclass` throws during mod construction. Eleven cart classes reach `createKey` through this one call site, which is the only place in Railcraft that touches `sun.reflect` at all.

`RailcraftCallerClassTransformer` makes the same move Fugue does, to a target that counts the way the original did. Both possible owners are matched, because the two transformers are not ordered against each other: on plain Forge the call still says `sun.reflect`, and after Fugue has run it says Cleanroom. The result behaves identically on Java 8 and on a modern JVM, since it no longer reaches `sun.reflect` either way.

The replacement was checked against the real thing: on a JDK 8, calling `sun.reflect.Reflection.getCallerClass(n)` and this one from the same frame returns the same class at depths 1, 2 and 3.

The patch is confined to Railcraft's own class on purpose. Rewriting every caller lookup in the game would also catch mods already built around the shifted numbering, and turn their working code into the bug this removes.

### Containers the server hardened that are not carried yet

The same shape recurs across the packs: the server patched the containers that are known duplication routes. Three are carried above, in IndustrialCraft 2, AE2 Stuff and Twilight Forest, and Thaumcraft's with them. One more is measured but not ported, because it needs a branch inside an existing method rather than an append, which would mean recomputing stack map frames from inside a transformer.

- **ExtraBotany** handles `ClickType.SWAP` in `ContainerHandbag.slotClick`, which is the hotbar-key swap that otherwise duplicates the bag, and calls `detectAndSendChanges` from `transferStackInSlot`.

It is client prediction only. The server holds the authoritative inventory and corrects the client on its next window update, so the symptom is a wrong-looking slot rather than a duplicated item.

## Carried data

Not everything is code.

### The mod list the client reports

Forge's handshake check is not symmetric. A client accepts whatever the server reports: `DefaultNetworkChecker.checkCompatible` returns immediately when asked about the server side, which is why only a mod with a handler of its own, like Ender IO's, can refuse from the client. The server is strict in the other direction. `NetworkModHolder.acceptVersion` falls back to `container.getVersion().equals(remote)` when a mod declares no `acceptableRemoteVersions`, so a client reporting a genuine version where the server expects a relabelled one is rejected, mod by mod.

Every pack currently passes because it ships the server's own jars. Installing the published releases instead means the reported list has to say what the server expects, which is what [hidemymods](https://github.com/Kitty-Hivens/hidemymods) is for. It replaces the outbound list wholesale, so the config has to name every mod the server requires rather than only the relabelled ones. [`pack/hidemymods-spoof/`](pack/hidemymods-spoof/) holds one per pack, generated from what each server advertises in its own status ping, to be installed as `hidemymods-spoof.json` in the instance directory.

For Galaxy the mods whose reported version has to differ from the genuine release are:

| mod id | genuine release declares | server expects |
|---|---|---|
| `enderio` and its ten sibling ids | `5.2.61` | `5.2.0` |
| `quark` | `r1.6-179` | `r1.6-180` |
| `ae2stuff` | `0.7.0.4` | `0.7.0.5-DEV` |
| `autoreglib` | `1.3-32` | `1.3-33` |
| `ironchest` | `1.12.2-7.0.67.844` | `1.12.2-7.0.72.847` |
| `matteroverdrive` | `0.7.0.16` | `0.7.0.0` |

Industrial needs the same minus Ender IO and Matter Overdrive. Everything else in both packs reports the same string either way.

One entry needs care: Galaxy's server registers `micdoodlecore` with an empty version, and an omitted entry reads as the mod being absent rather than as having no version. The generated config keeps it, and hidemymods carries an empty version through to the wire rather than treating the entry as malformed.

### IC2 recipes

IC2 reads `config/ic2/<name>.ini` from the instance directory and falls back to the jar asset only when that file is absent or unreadable, so the server's recipe set transfers as a plain file. [`pack/config/ic2/macerator.ini`](pack/config/ic2/macerator.ini) is that file, copied verbatim out of the server's jar, and belongs in the pack as an asset rather than in this jar. It differs from upstream in four ways:

- plates macerate to eight small dust instead of one full dust, for iron, copper, tin, gold, lead, bronze, obsidian and lapis
- dense plates yield eight dust instead of nine, and the commented out dense steel entry is live, mapping to iron dust
- crushed ores, purified crushed ores, netherrack, ender pearls, ender eyes and emeralds no longer macerate at all
- iridium is keyed on `ic2:misc_resource#iridium_ore` rather than the `gemIridium` ore dictionary entry, and Applied Energistics certus quartz, nether quartz and fluix crystals are added

## What it deliberately leaves alone

Most of the rebuilt jars turned out to need nothing. Comparing each against the release it was built from, method by method and by what each method actually does rather than by its bytes, gives:

| Mod | Classes byte-identical to the release | Verdict |
|---|---|---|
| Iron Chests | 100% | only resources differ, and every translated string matches |
| Loot Capacitor Tooltips | 100% | same |
| Hats | 98.5% | one class rebuilt, reads a field where the release calls the getter that returns it |
| Hat Stand | 70% | three classes rebuilt, no method does anything different |
| Applied Energistics 2 | 98.6% | twenty-one classes carry upstream fixes, none of which changes what crosses the wire |
| Gravitation Suite | 75.9% | the wrench gained integrations with other mods, all of it acted on by the server |

Applied Energistics was the one worth checking closely, because the pattern terminal's buttons send a value to the server. Both builds send the same two literals, the release through named constants and the server's copy inlined, so there is nothing to reconcile.

Every one of these releases also resolves cleanly against the rest of the pack. Advanced Solar Panels is the single exception, and its three unresolved references are the ones patched above.

## Why no mixins

Mixin is available in these packs, through MixinBooter on Forge and built in under Cleanroom, so this is a choice rather than a constraint.

None of the patches here is a good fit for it. Two are descriptor and owner rewrites at a call site, which is the one thing mixin deliberately does not express. One splices a conjunction into a method that has to keep the enclosing try/finally and its stack map frames exactly as compiled. One replaces a method body outright, which mixin would do slightly more legibly, but not enough to take on a load-time dependency for.

The calculation changes if a patch grows past rewriting instructions into carrying real logic. That is what the helper class behind the Railcraft patch is for: the transformer moves a single call, and everything worth reading lives in ordinary Java next to it.

## Building

Needs a Java 8 JDK (a JRE is not enough: ForgeGradle refuses it).

```
JAVA_HOME=/path/to/jdk8 ./gradlew build -PmodVersion=1.1.0
```

The jar lands in `build/libs`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
