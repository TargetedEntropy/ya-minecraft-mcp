# YAMC MCP

A **client-side only** mod for **Fabric and NeoForge** that runs a [Model Context Protocol](https://modelcontextprotocol.io) server
inside Minecraft, so AI assistants such as Claude Code can read live game state: player, inventory, world, registries, tags,
recipes (modded too) and more. They can also act in game. Servers do not need the mod.

## Supported versions

One jar per Minecraft version and loader, named `yamcmcp-<loader>-<mod version>+<minecraft>.jar`:

| Jar built for | Fabric runs on | NeoForge runs on | Java |
|---|---|---|---|
| 1.21.1 | 1.21 – 1.21.1 | 1.21.1 | 21 |
| 1.21.4 | 1.21.4 | 1.21.4 | 21 |
| 1.21.5 | 1.21.5 | 1.21.5 | 21 |
| 1.21.8 | 1.21.7 – 1.21.8 | 1.21.8 | 21 |
| 1.21.10 | 1.21.9 – 1.21.10 | 1.21.10 | 21 |
| 1.21.11 | 1.21.11 | 1.21.11 | 21 |
| 26.1.2 | 26.1.2 | 26.1.2 | 25 |
| 26.2 | 26.2.x | 26.2.x | 25 |
| 26.3 | 26.3.x | 26.3.x | 25 |

The Fabric jars need [Fabric API](https://modrinth.com/mod/fabric-api).

## Install & connect

1. Download the jar for your Minecraft version and loader from the [releases](https://github.com/TargetedEntropy/ya-minecraft-mcp/releases)
   (or build it, see [Development](#development)) and drop it into your `mods/` folder.
2. Launch the game. The server listens on `http://127.0.0.1:25599/mcp`.
3. In game, run `/yamc connect`. It copies the setup command to your clipboard:
   ```
   claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp --header "Authorization: Bearer <token>"
   ```
   The same command is written to `config/yamcmcp-connect.txt`, and the token is in `config/yamcmcp-token.txt`.
4. `/yamc status` shows the request count and the last connected client.

### Push notifications (Claude Code channels)

With the plain HTTP connection above, Claude has to poll `events_since` for events. To have events like death, damage,
dimension changes and level-ups **pushed into the conversation as they happen**, connect through the bundled stdio
bridge, which Claude Code loads as a [channel](https://code.claude.com/docs/en/channels):

```
claude mcp add minecraft -- java -cp "/path/to/mods/yamcmcp-<version>.jar" dev.yamc.mcp.bridge.StdioBridge \
    --url http://127.0.0.1:25599/mcp --token-file "/path/to/.minecraft/config/yamcmcp-token.txt"
claude --dangerously-load-development-channels server:minecraft
```

`/yamc connect channel` copies this command with the real paths filled in. It's also written to `config/yamcmcp-connect.txt`.

- The bridge uses only the JDK, so the Java that runs Minecraft works. Start the game before Claude Code; if the game
  restarts, the bridge reconnects the event stream automatically.
- Events arrive as `<channel source="minecraft" event="death" seq="42">Player DIED at ...</channel>`.
- Pushed by default: `death`, `damage` (at most once per 5 s), `dimension_change`, `level_up`, `gamemode_change`,
  `join_world`, `leave_world`. Claude can change this at runtime with the `push_settings` tool. To change the defaults,
  set `push.eventTypes` in the config.
- **Chat is not pushed by default.** Other players' chat would land in Claude's context, and with action tools enabled that
  is a prompt-injection risk. If you opt in, pushed chat is labelled as untrusted.
- Channels are a Claude Code research preview. They need a claude.ai or Console login, and Team/Enterprise organizations
  must enable them first.

Singleplayer tip: press **F3+P** to turn off pause-on-focus-loss. Otherwise the game pauses while you are in your terminal,
and movement or mining tools can't run.

## What data is available where

| Data | Remote server | Singleplayer / LAN host |
|---|---|---|
| Static registries (blocks, items, entities, fluids, effects, ...; modded registries too) | ✅ | ✅ |
| Synced data-driven registries (biome, damage_type, enchantment, dimension_type, trims, ...) and their tags | ✅ | ✅ |
| Recipes, modded included | ✅ up to 1.21.1 (ingredient tags flattened to item lists); ❌ from 1.21.2, servers no longer send recipes to clients | ✅ from the integrated server, tags intact |
| Structures, structure sets, worldgen features, noise, loot tables, locate | ❌ server-only data | ✅ |

Every response reports a `source` (`static`, `synced`, `integrated_server`, `client`).

## Tools (53)

**Player:** `player_status`, `player_inventory`
**World:** `world_info`, `look_target`, `block_at`, `blocks_at`, `scan_area`, `find_blocks`, `nearby_entities`, `entity_info`, `players_online`, `open_container`, `hud`
**Registries & tags:** `registry_list`, `registry_entries`, `registry_entry`, `tags_list`, `tag_members`, `tags_of`
**Recipes:** `recipe_types`, `recipes_search`, `recipe_get`, `craft_plan`, `craftable_now`
**Info:** `mods_list`, `item_tooltip`, `advancements`, `statistics`, `chat_history`, `events_since`, `wait_for_event`, `push_settings`, `screenshot`, `export_data`
**Singleplayer server data:** `loot_table`, `structure_info`, `locate_structure`, `locate_biome`
**Fishing:** `fishing_status`, `fish` (casts, waits for a bite and reels in; bites and catches are also `fish_bite` / `fish_caught` events)
**Actions:** `send_chat`, `run_command`, `look_at`, `select_slot`, `interact`, `mine_block`, `move`, `container_click`, `open_inventory`, `close_screen`, `highlight`, `clear_highlights`, `notify_player`

List tools share the same filters (`namespace`, `query`, which matches the id or the display name, and `regex`) and paging (`page_size`, `cursor` → `next_cursor`).

## Config

NeoForge: `config/yamcmcp-client.toml`. Fabric: `config/yamcmcp.properties` (same keys without the section prefix, e.g. `port`,
`enableActions`, `push.eventTypes`; read at startup, so restart the game after editing).

- `server.port` (25599), `server.bindAddress` (127.0.0.1), `server.requireToken` (true), `server.clientThreadTimeoutMs`
- `tools.enableActions` (true): set false for a read-only mod. Action tools are then hidden.
- `tools.enableIntegratedServerTools` (true), `tools.enableLocate` (true), `tools.eventBufferSize`
- `push.enabled` (true), `push.eventTypes` (empty = defaults listed above)

## Security

- Binds to loopback only.
- Requires a bearer token.
- Rejects browser requests from non-localhost `Origin`s (DNS-rebinding protection).
- With actions enabled, the AI can chat and run commands as you. On multiplayer servers that is visible to others and may break server rules.

## Development

One source tree builds every version and loader with [Stonecutter](https://stonecutter.kikugie.dev). Each pair is a Gradle
project named `<minecraft>-<loader>` (e.g. `1.21.11-fabric`); versions and dependencies are in `stonecutter.properties.toml`.
Needs JDK 21 and JDK 25 (26.x).

- `./gradlew buildAll`: every jar, collected in `build/libs/<mod version>/`. One node: `./gradlew :26.3-neoforge:build`.
- `./gradlew :<node>:runClient`, or `:<node>:runTestClient` for a client in `run-test/<node>/` that auto-creates and joins a
  singleplayer world named `mcptest`. Headless: `xvfb-run -a ./gradlew :<node>:runTestClient`.
- `scripts/smoke-test.sh <node>`: launches that test client, calls 35 tools over MCP and reports pass/fail.
- The source is kept in the `1.21.1-neoforge` state. Version-specific code is marked with Stonecutter comments
  (`//? if >=1.21.2 {` ... `//?} else {` ... `//?}`). To work on another version in the IDE, run
  `./gradlew "Set active project to 26.3-fabric"`, and `./gradlew "Reset active project"` before committing.
- Where version code lives: `platform/` (what a loader provides), `fabric/` and `neoforge/` (entrypoints, events, config),
  `recipe/Recipes` (the recipe API), `util/Mc` (small API differences), `util/Inventories`, `client/Markers` (rendering).
  Tools call these instead of writing version conditions themselves.

## Publishing to CurseForge

`scripts/publish-curseforge.sh [--type release|beta|alpha] [--changelog notes.md] [--only <node>]... [--no-build] [--dry-run]`
builds every jar and uploads each one, tagged with its Minecraft releases (`mod.mc_releases`), loader, Client and Java version.
Use `--dry-run` first to see what would be uploaded.

- Token: from `CURSEFORGE_TOKEN`, or from a file containing `TOKEN=...` (default `~/.config/curseforge.cred`).
- Project id: from `CURSEFORGE_PROJECT_ID`, or `mod.curseforge_project_id` in `stonecutter.properties.toml`.

## License

MIT, see [LICENSE](LICENSE).
