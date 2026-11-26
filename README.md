# YAMC MCP

A **client-side only** NeoForge 1.21.1 mod that runs a [Model Context Protocol](https://modelcontextprotocol.io) server
inside Minecraft, so AI assistants such as Claude Code can read live game state: player, inventory, world, registries, tags,
recipes (modded too) and more. They can also act in game. Servers do not need the mod.

## Install & connect

1. Build (Java 21): `git clone https://github.com/TargetedEntropy/ya-minecraft-mcp && cd ya-minecraft-mcp && ./gradlew build` → `build/libs/yamcmcp-<version>.jar`, then drop it into your NeoForge 1.21.1 `mods/` folder.
2. Launch the game. The server listens on `http://127.0.0.1:25599/mcp`.
3. In game, run `/yamcmcp connect`. It copies the setup command to your clipboard:
   ```
   claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp --header "Authorization: Bearer <token>"
   ```
   The same command is written to `config/yamcmcp-connect.txt`, and the token is in `config/yamcmcp-token.txt`.
4. `/yamcmcp status` shows the request count and the last connected client.

Singleplayer tip: press **F3+P** to turn off pause-on-focus-loss. Otherwise the game pauses while you are in your terminal,
and movement or mining tools can't run.

## What data is available where

| Data | Remote server | Singleplayer / LAN host |
|---|---|---|
| Static registries (blocks, items, entities, fluids, effects, ...; modded registries too) | ✅ | ✅ |
| Synced data-driven registries (biome, damage_type, enchantment, dimension_type, trims, ...) and their tags | ✅ | ✅ |
| Recipes, modded included | ✅ (ingredient tags flattened to item lists) | ✅ from the integrated server, tags intact |
| Structures, structure sets, worldgen features, noise, loot tables, locate | ❌ server-only data | ✅ |

Every response reports a `source` (`static`, `synced`, `integrated_server`, `client`).

## Tools (49)

**Player:** `player_status`, `player_inventory`
**World:** `world_info`, `look_target`, `block_at`, `scan_area`, `find_blocks`, `nearby_entities`, `entity_info`, `players_online`, `open_container`, `hud`
**Registries & tags:** `registry_list`, `registry_entries`, `registry_entry`, `tags_list`, `tag_members`, `tags_of`
**Recipes:** `recipe_types`, `recipes_search`, `recipe_get`, `craft_plan`, `craftable_now`
**Info:** `mods_list`, `item_tooltip`, `advancements`, `statistics`, `chat_history`, `events_since`, `wait_for_event`, `screenshot`, `export_data`
**Singleplayer server data:** `loot_table`, `structure_info`, `locate_structure`, `locate_biome`
**Actions:** `send_chat`, `run_command`, `look_at`, `select_slot`, `interact`, `mine_block`, `move`, `container_click`, `open_inventory`, `close_screen`, `highlight`, `clear_highlights`, `notify_player`

List tools share the same filters (`namespace`, `query`, which matches the id or the display name, and `regex`) and paging (`page_size`, `cursor` → `next_cursor`).

## Config (`config/yamcmcp-client.toml`)

- `server.port` (25599), `server.bindAddress` (127.0.0.1), `server.requireToken` (true), `server.clientThreadTimeoutMs`
- `tools.enableActions` (true): set false for a read-only mod. Action tools are then hidden.
- `tools.enableIntegratedServerTools` (true), `tools.enableLocate` (true), `tools.eventBufferSize`

## Security

- Binds to loopback only.
- Requires a bearer token.
- Rejects browser requests from non-localhost `Origin`s (DNS-rebinding protection).
- With actions enabled, the AI can chat and run commands as you. On multiplayer servers that is visible to others and may break server rules.

## Development

- `./gradlew runClient`: normal dev client.
- `./gradlew runTestClient`: client in `run-test/` that auto-creates and joins a singleplayer world named `mcptest`
  (`-Dyamcmcp.devAutoWorld`). Headless: `xvfb-run -a ./gradlew runTestClient`.
