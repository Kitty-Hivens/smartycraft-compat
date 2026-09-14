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

### IndustrialCraft 2 addons rebuilt against a newer API

Three of the pack's IC2 addons are not the published releases, and none of them is a relabel. Each is the genuine release with one or two classes recompiled, because IC2 changed an API under them and the old signatures are gone. A pack that installs the real jars gets `NoSuchMethodError` rather than a version disagreement.

Two of them meet the same change. IC2's inventory slots used to be constructed from a `TileEntityInventory` and now take the interface:

```
InvSlot(IInventorySlotHolder<?>, String, Access, int, InvSide)
InvSlotOutput(IInventorySlotHolder<?>, String, int)
InvSlotProcessable(IInventorySlotHolder<?>, String, int, IMachineRecipeManager)
```

**Advanced Solar Panels** last shipped in December 2018, and those three call sites are the only unresolved references it has against the IC2 in the pack. **Advanced Machines**, by the same author, carries the same three in its heating machine: the server's copy differs from the published 61.0.1 in that one class and in nothing else at all, 191 of 192 entries byte for byte.

`Ic2SlotHolderTransformer` handles both. It is a descriptor rewrite rather than a code change, since `TileEntityInventory` implements `IInventorySlotHolder` directly, so the value already on the stack satisfies the new parameter and nothing about either mod's behaviour moves. Only constructor calls are considered, only when the owner is one of IC2's slot classes, and only inside those two packages: a blanket descriptor substitution across every class the game loads would be a far larger promise than this needs to make.

Patching the published Advanced Machines this way produces a class identical to the server's, instruction for instruction, apart from an assertion message its build dropped.

**BC Fuels For IC2** meets a different one. It registers its eight fuels through `ISemiFluidFuelManager.addFluid(String, int, double)` and current IC2 declares only `addFluid(String, long, long)`, so the stock jar throws while registering the first fuel. Here the server's copy differs from the published v0.2 in one class, six of seven entries byte for byte.

A descriptor rewrite is not enough for this one: the arguments are of the wrong kinds, not merely of the wrong declared types, so `SemiFluidFuelWidenTransformer` widens them. The double sits above the int on the stack, so it is parked in a scratch local while the int underneath is widened, then brought back and widened in place. Truncating the energy is what the newer API asks for, and every value this mod passes comes from its own config as a whole number anyway. No branch and no jump target, so nothing in the method's frames is disturbed.

None of the three needs a handshake spoof. Each declares the same version as the release it was built from, which is why nothing about them looked wrong until their bytes were compared.

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

### ExtraBotany

Pressing a hotbar number while pointing at a slot swaps the two. Point at a slot inside an open handbag and press the number the handbag itself sits on, and the container is asked to move the bag into the inventory the bag is providing. The server's copy refuses that click outright. It also calls `detectAndSendChanges` at the end of a shift-click, which the published release leaves to whatever sends changes next.

`HandbagSwapTransformer` carries both, in two different shapes, because the two changes are.

The refusal is a method the published release does not have at all. `ContainerHandbag` inherits `slotClick` straight from `Container`, so there is no body to splice a branch into and the override is added whole instead. Being ours, its one branch and one merge come with the frame written out rather than recomputed, which is not the same risk as asking a transformer to recompute the frames of code somebody else compiled. The condition itself lives in ordinary Java and only the early return is bytecode. The bag is compared by identity, not equality, which is what makes the click self-referential: two identical bags in different slots are a different situation and are left alone.

The notification is a plain insert after the existing `onTake`, with no branch and nothing to recompute. Both halves are skipped when the class already carries them, so a pack shipping the server's own jar is not patched twice.

Patching the published release this way produces a class identical to the server's, instruction for instruction, apart from the condition being a call rather than inlined.

### Better Chat

The server draws the sender's head, hat layer and all, beside each chat message. The published release has no such thing, so a client running it shows chat with the heads simply missing and nothing to explain why.

The name has nowhere obvious to travel. A chat message arrives as formatted text and the formatting is the server's own. The server's build sends it outright instead, in the shift-click event of the message's style under the `CHANGE_PAGE` action, which nothing else in chat uses. `ChatHeadsTransformer` reads the same field first, because a name the server states is not a guess.

Not every SmartyCraft server sets it. The one the Industrial pack connects to does not, which is presumably why that pack never shipped the chat mod at all, while Galaxy, RPG, Nevermine and TechnoMagic did. So when the field is absent the sender is read out of the message instead: only the segment before the first `:` or `>`, matched against the tab list, longest name winning. That segment is where every chat format puts the sender and where nothing else goes, which is what keeps a head off "someone joined the game" and off a message that merely mentions a player.

Five call sites, no new branches. The author is read once per message at the top of the method that splits it into lines. The two list insertions in that method become calls that tag the line being added, told apart by the field each one reads rather than by their order, because only the first line of a wrapped message carries the head. In the drawing loop the text call and the background call take the line as an extra argument and decide for themselves.

The author cannot be kept on the line the way the server's build keeps it: that build subclasses `ChatLine` to hold it, and giving a foreign class a field is not something a call-site rewrite can do. A weak map from line to author holds it here instead, so a line that scrolls out of the hundred chat keeps takes its entry with it.

Two things are deliberately not copied. The server's build widens the chat background by the head's width on every line, so a server that sets no author still gets a background wider than vanilla; here the widening follows the head. And its hit test is left byte for byte as the release has it, which is worth saying plainly: **the quick reply is not part of this patch**. Whatever a click does comes from the click event the server attaches to the message, and that works with the published release untouched. What the release loses is the head and the space made for it.

The shift-click event is a Forge addition that arrived during 1.12.2 rather than at the start, and the packs run a build newer than the one this mod compiles against. It is asked for by name once and remembered, so a client on the older side loads the mod fine and simply draws no heads.

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

### Damage Indicators

On joining, the server tells the client which of the mod's features it allows. The client answers by writing three lines into chat:

```java
public DIPermissions onMessage(DIPermissions message, MessageContext ctx) {
    Handler.processPermissions(DIMod.proxy.getPlayer(), (byte) 0);
    return null;
}

public static void processPermissions(EntityPlayer player, byte toggles) {
    ...
    player.sendMessage(new TextComponentString("[DamageIndicators] ...Mouseovers enabled."));
    // and five more, none of them asking whether there is a player
}
```

A mod's channel handler runs on the network thread the moment its packet lands, which is during the handshake, before the client has built its player. `getPlayer()` hands back nothing and the send is made on it.

That is not a lost chat line. An exception out of a channel handler is a fatal packet error to the network dispatcher, and it terminates the connection: `There was a critical exception handling a packet on channel DIMod`, then `Network Disconnect: A fatal error has occurred, this connection is terminated`. The client is left with no world and no player while the packets for both keep arriving, the integrated server stops because its only player left, and the loading screen sits at nought per cent. No crash report is written, because nothing crashed.

It is a race, which is why it looks arbitrary. The first world entered in a session has to be generated, which takes long enough that the player exists before the packet is handled. A world entered afterwards is already on disk and opens in seconds, and the packet wins. A server that answers quickly produces the same race, so it reaches multiplayer too, on the second join of a session.

`DamageIndicatorsNoticeTransformer` routes the six sends through a helper that checks first. The flags the same method sets are still set, and a client that does have a player still gets its three lines. Swapping a call site carries no branch, so no frame in the mod's own method is disturbed.

### Containers the server hardened

The same shape recurs across the packs: the server patched the containers that are known duplication routes. All of them are carried above, in IndustrialCraft 2, AE2 Stuff, Twilight Forest, Thaumcraft and ExtraBotany.

None of them is a duplication hole on an unpatched client. The server holds the authoritative inventory and corrects the client on its next window update, so the symptom is a wrong-looking slot rather than a duplicated item. They are carried because a client that predicts one thing and is corrected to another is the kind of desync players report as an item disappearing.

### Installing it over the server's own jars

A pack does not have to move to the published releases all at once. Repinning happens mod by mod, so for a while a pack holds some genuine jars and some of the server's, with this mod loaded over both.

That is safe. Every patch here either finds no site to change in a jar that already carries the change, or leaves the behaviour where it already was:

- the three IC2 addons, AE2 Stuff and Railcraft look for a call site the server's jar no longer has, so nothing matches
- Ender IO rewrites the handler to the same body whichever version it started from
- the Cache's write lands inside the branch the server's copy already made, and the hand mirror is resolved to the mirror the server's copy would have found, so both are fixed points
- the batch crafter gains a second copy of a test the server's copy already makes, which cannot change the answer
- the uncrafting table and the handbag are the two that would actually differ, and both check first and leave a jar that carries the change alone

## Carried data

Not everything is code.

### The mod list the client reports

Forge's handshake check is not symmetric. A client accepts whatever the server reports: `DefaultNetworkChecker.checkCompatible` returns immediately when asked about the server side, which is why only a mod with a handler of its own, like Ender IO's, can refuse from the client. The server is strict in the other direction. `NetworkModHolder.acceptVersion` falls back to `container.getVersion().equals(remote)` when a mod declares no `acceptableRemoteVersions`, so a client reporting a genuine version where the server expects a relabelled one is rejected, mod by mod.

Every pack currently passes because it ships the server's own jars. Installing the published releases instead means the reported list has to say what the server expects, which is what [hidemymods](https://github.com/Kitty-Hivens/hidemymods) is for. It replaces the outbound list wholesale, so the config has to name every mod the server requires rather than only the relabelled ones. The config is installed as `hidemymods-spoof.json` in the instance directory.

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

IC2 reads `config/ic2/<name>.ini` from the instance directory and falls back to the jar asset only when that file is absent or unreadable, so the server's recipe set transfers as a plain file. That file belongs in the pack as an asset rather than in this jar. It differs from upstream in four ways:

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

Every one of these releases also resolves cleanly against the rest of the pack. The three IC2 addons are the exception, and their unresolved references are the ones patched above.

## Why no mixins

Mixin is available in these packs, through MixinBooter on Forge and built in under Cleanroom, so this is a choice rather than a constraint.

One of them now carries real logic: the chat heads keep a map, resolve a skin and draw. That is the shape where mixin earns its keep, and it stays out here only because it would be the tenth patch in a jar whose other nine are call-site rewrites.

Most of the patches here are a poor fit for it. Two are descriptor and owner rewrites at a call site, which is the one thing mixin deliberately does not express. One splices a conjunction into a method that has to keep the enclosing try/finally and its stack map frames exactly as compiled. One replaces a method body outright, which mixin would do slightly more legibly, but not enough to take on a load-time dependency for.

The handbag override is the one that would genuinely read better as a mixin. Adding a method to a target class is the case mixin handles best, and it would come with its frames computed rather than written out by hand. It is one method against three call-site rewrites mixin cannot express at all, so it goes the same way as the rest rather than splitting the mod across two mechanisms for it.

The calculation changes if a patch grows past rewriting instructions into carrying real logic. That is what the helper class behind the Railcraft patch is for: the transformer moves a single call, and everything worth reading lives in ordinary Java next to it.

## Building

Needs a Java 8 JDK (a JRE is not enough: ForgeGradle refuses it).

```
JAVA_HOME=/path/to/jdk8 ./gradlew build -PmodVersion=1.1.0
```

The jar lands in `build/libs`.

## License

Apache License 2.0, see [LICENSE](LICENSE).
