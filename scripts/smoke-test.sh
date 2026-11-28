#!/usr/bin/env bash
# Launch one node's dev test client (auto-joins world "mcptest"), call a battery of tools over MCP,
# print one line per tool and exit non-zero if any failed.
#   scripts/smoke-test.sh 1.21.1-fabric [--keep]     (--keep leaves the client running)
# Needs xvfb-run when there is no display.
set -uo pipefail
NODE=${1:?usage: smoke-test.sh <node> [--keep]}
KEEP=${2:-}
cd "$(dirname "$0")/.."
LOG=build/smoke/$NODE.log
mkdir -p build/smoke
PORT=25599

port_busy() { (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; }
if port_busy; then echo "port $PORT already in use (another client running?)"; exit 2; fi

# Fresh game folders show the accessibility onboarding screen instead of the title screen.
mkdir -p "run-test/$NODE"
[ -f "run-test/$NODE/options.txt" ] || printf 'onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nsoundCategory_master:0.0\n' >"run-test/$NODE/options.txt"

# Headless (Xvfb) has no GLX visual for NeoForge's early loading window on newer versions: turn it off.
if [ -z "${DISPLAY:-}" ] && [[ "$NODE" == *-neoforge ]]; then
    mkdir -p "run-test/$NODE/config"
    F="run-test/$NODE/config/fml.toml"
    if [ -f "$F" ]; then sed -i 's/^earlyWindowControl = true/earlyWindowControl = false/' "$F"; else echo 'earlyWindowControl = false' >"$F"; fi
fi

RUN=(./gradlew ":$NODE:runTestClient" --no-configuration-cache)
[ -z "${DISPLAY:-}" ] && RUN=(xvfb-run -a "${RUN[@]}")
"${RUN[@]}" >"$LOG" 2>&1 &
GRADLE_PID=$!

game_pids() { # JVMs running this node's game (their command line names the node's build dir)
    for p in $(pgrep java); do tr '\0' ' ' <"/proc/$p/cmdline" 2>/dev/null | grep -q "versions/$NODE/" && echo "$p"; done
}
stop() {
    [ "$KEEP" = "--keep" ] && return
    local pids; pids="$(game_pids) $GRADLE_PID"
    kill $pids 2>/dev/null
    for _ in $(seq 1 20); do kill -0 $pids 2>/dev/null || break; sleep 1; done
    kill -9 $pids 2>/dev/null
    wait "$GRADLE_PID" 2>/dev/null
}
trap stop EXIT

TOKEN_FILE=run-test/$NODE/config/yamcmcp-token.txt
for _ in $(seq 1 600); do
    grep -q 'YAMC MCP server listening' "$LOG" 2>/dev/null && break
    if ! kill -0 "$GRADLE_PID" 2>/dev/null || grep -qE 'Incompatible mods found|Crash report|---- Minecraft Crash|Mixin apply.* failed|BUILD FAILED' "$LOG"; then
        echo "client failed before the server started; see $LOG"; grep -E 'ERROR|Exception|error:|requires|Caused by' "$LOG" | head -15; exit 1
    fi
    sleep 1
done
grep -q 'YAMC MCP server listening' "$LOG" || { echo "server did not start; see $LOG"; exit 1; }
TOKEN=$(cat "$TOKEN_FILE")

call() { # tool json-args -> prints result JSON
    curl -s -m 60 "http://127.0.0.1:$PORT/mcp" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}"
}

# Wait until the player is in the world.
for _ in $(seq 1 300); do call player_status '{}' | grep -q 'health' && break; sleep 1; done
sleep 3

FAIL=0
check() { # tool args [expected substring]
    local out ok
    out=$(call "$1" "$2")
    if echo "$out" | grep -q '"isError":false' && { [ -z "${3:-}" ] || echo "$out" | grep -q -- "$3"; }; then ok=PASS; else ok=FAIL; FAIL=$((FAIL + 1)); fi
    printf '%s %-18s %s\n' "$ok" "$1" "$(echo "$out" | head -c 160)"
}

check player_status '{}' health
check player_inventory '{}'
check world_info '{}'
check mods_list '{"query":"yamc"}' yamcmcp
check send_chat '{"message":"smoke test chat"}'
check run_command '{"command":"time set day"}'
sleep 2
check events_since '{"since":0,"limit":1000}' join_world
check chat_history '{"limit":5}' 'smoke test chat'
check block_at '{"x":0,"y":64,"z":0}'
check scan_area '{"radius":3}'
check nearby_entities '{"radius":32}'
check look_target '{}'
check hud '{}'
check registry_list '{}'
check registry_entry '{"registry":"minecraft:worldgen/biome","id":"minecraft:plains"}' plains
check registry_entry '{"registry":"minecraft:item","id":"minecraft:diamond"}' diamond
check tags_of '{"registry":"minecraft:item","id":"minecraft:oak_planks"}' planks
check tag_members '{"registry":"minecraft:item","tag":"minecraft:logs"}' log
check recipe_get '{"id":"minecraft:oak_planks"}' oak_planks
check recipes_search '{"output":"minecraft:crafting_table"}' crafting_table
check craft_plan '{"item":"minecraft:chest"}'
check item_tooltip '{"item":"minecraft:diamond_sword"}'
check advancements '{}'
check statistics '{}'
check loot_table '{"id":"minecraft:blocks/oak_log"}'
check structure_info '{"id":"minecraft:village_plains"}'
check locate_biome '{"biome":"minecraft:plains"}'
check fishing_status '{}' holding_rod
check highlight '{"x":0,"y":64,"z":0,"label":"smoke"}' marker_id
check screenshot '{}'
check screenshot '{"max_width":320,"dy":6,"pitch":60}' distance_from_player
check screenshot '{"max_width":320,"yaw":180}' distance_from_player
check clear_highlights '{}'
check look_at '{"yaw":0,"pitch":0}'
check select_slot '{"slot":1}'
check export_data '{"what":["tags"]}' directory
# Mod development tools
check logs '{"logger":"yamcmcp","limit":20}' 'YAMC MCP server listening'
check exceptions '{}' distinct
check asset_audit '{"namespace":"minecraft","limit":5}' entries_with_problems
check block_inspect '{}' in_sync
check reload '{"what":"all"}' duration_ms

echo "$NODE: $FAIL failed"
[ "$FAIL" = 0 ]
