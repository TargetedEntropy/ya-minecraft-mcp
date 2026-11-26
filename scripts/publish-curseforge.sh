#!/usr/bin/env bash
# Upload the built mod jar to CurseForge.
#
# Usage: scripts/publish-curseforge.sh [--type release|beta|alpha] [--changelog FILE] [--dry-run]
#
# Needs:
#   - CurseForge API token: env CURSEFORGE_TOKEN, or a file (default ~/.config/curseforge.cred)
#     containing TOKEN=<token>. Create one at https://legacy.curseforge.com/account/api-tokens
#   - Project id: env CURSEFORGE_PROJECT_ID, or curseforge_project_id in gradle.properties
# The token is never printed or written anywhere.
set -euo pipefail
cd "$(dirname "$0")/.."

TYPE=beta; CHANGELOG=""; DRY=0
CRED_FILE="${CURSEFORGE_CRED_FILE:-$HOME/.config/curseforge.cred}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --type) TYPE="$2"; shift 2 ;;
    --changelog) CHANGELOG="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done

prop() { sed -n "s/^$1=//p" gradle.properties | tr -d '\r'; }
VERSION=$(prop mod_version); MC=$(prop minecraft_version); MODID=$(prop mod_id)
PROJECT="${CURSEFORGE_PROJECT_ID:-$(prop curseforge_project_id)}"
[[ -n "$PROJECT" ]] || { echo "Set CURSEFORGE_PROJECT_ID or curseforge_project_id in gradle.properties" >&2; exit 1; }
TOKEN="${CURSEFORGE_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$CRED_FILE" ]]; then TOKEN=$(sed -n 's/^TOKEN=//p' "$CRED_FILE" | tr -d '\r\n'); fi
[[ -n "$TOKEN" ]] || { echo "No CurseForge token (CURSEFORGE_TOKEN or $CRED_FILE)" >&2; exit 1; }

JAR="build/libs/${MODID}-${VERSION}.jar"
./gradlew build -q --console=plain
[[ -f "$JAR" ]] || { echo "missing $JAR" >&2; exit 1; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
API=https://minecraft.curseforge.com/api
curl -sf -H "X-Api-Token: $TOKEN" "$API/game/version-types" -o "$TMP/types.json"
curl -sf -H "X-Api-Token: $TOKEN" "$API/game/versions" -o "$TMP/versions.json"

if [[ -z "$CHANGELOG" ]]; then
  printf '%s\n' "YAMC MCP ${VERSION} for NeoForge ${MC}. See https://github.com/TargetedEntropy/ya-minecraft-mcp for setup and the full changelog." > "$TMP/changelog.md"
  CHANGELOG="$TMP/changelog.md"
fi

# Resolve game-version ids by name: Minecraft version, NeoForge, Client, Java 21.
python3 -I - "$TMP" "$MC" "${MODID} ${VERSION}" "$TYPE" "$CHANGELOG" > "$TMP/metadata.json" <<'PY'
import json, sys
tmp, mc, name, rtype, changelog = sys.argv[1:6]
types = {t["id"]: t["slug"] for t in json.load(open(tmp + "/types.json"))}
versions = json.load(open(tmp + "/versions.json"))
def find(label, pred):
    for v in versions:
        if pred(v, types.get(v["gameVersionTypeID"], "")):
            return v["id"]
    sys.exit(f"CurseForge game version not found: {label}")
mc_slug = "minecraft-" + "-".join(mc.split(".")[:2])
ids = [
    find(mc, lambda v, t: v["name"] == mc and t == mc_slug),
    find("NeoForge", lambda v, t: v["name"] == "NeoForge" and t == "modloader"),
    find("Client", lambda v, t: v["name"] == "Client" and t == "environment"),
    find("Java 21", lambda v, t: v["name"] == "Java 21" and t == "java"),
]
print(json.dumps({
    "changelog": open(changelog).read(), "changelogType": "markdown",
    "displayName": "YAMC MCP " + name.split(" ", 1)[1] + " (NeoForge " + mc + ")",
    "gameVersions": ids, "releaseType": rtype,
}))
PY

echo "Project $PROJECT, file $JAR ($TYPE)"
python3 -I -c 'import json,sys; m=json.load(open(sys.argv[1])); m["changelog"]=m["changelog"][:80]+"..."; print(json.dumps(m, indent=1))' "$TMP/metadata.json"
if [[ $DRY -eq 1 ]]; then echo "dry run: not uploading"; exit 0; fi

HTTP=$(curl -s -o "$TMP/resp.json" -w "%{http_code}" -H "X-Api-Token: $TOKEN" \
  --form-string "metadata=$(cat "$TMP/metadata.json")" -F "file=@${JAR}" \
  "$API/projects/${PROJECT}/upload-file")
echo "HTTP $HTTP: $(cat "$TMP/resp.json")"
[[ "$HTTP" == 200 ]]
