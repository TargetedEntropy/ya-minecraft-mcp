#!/usr/bin/env bash
# Upload the built jars (every Minecraft version and loader) to CurseForge.
#
# Usage: scripts/publish-curseforge.sh [--type release|beta|alpha] [--changelog FILE] [--only NODE]... [--no-build] [--dry-run]
#   NODE is a Stonecutter node such as 1.21.11-fabric. Without --only, every jar in build/libs/<mod version>/ is uploaded.
#
# Needs:
#   - CurseForge API token: env CURSEFORGE_TOKEN, or a file (default ~/.config/curseforge.cred)
#     containing TOKEN=<token>. Create one at https://legacy.curseforge.com/account/api-tokens
#   - Project id: env CURSEFORGE_PROJECT_ID, or mod.curseforge_project_id in stonecutter.properties.toml
#   - Python 3.11+ (tomllib)
# The token is never printed or written anywhere.
set -euo pipefail
cd "$(dirname "$0")/.."

TYPE=beta; CHANGELOG=""; DRY=0; BUILD=1; ONLY=()
CRED_FILE="${CURSEFORGE_CRED_FILE:-$HOME/.config/curseforge.cred}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --type) TYPE="$2"; shift 2 ;;
    --changelog) CHANGELOG="$2"; shift 2 ;;
    --only) ONLY+=("$2"); shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --dry-run) DRY=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done

toml() { python3 -I -c 'import tomllib,sys; d=tomllib.load(open("stonecutter.properties.toml","rb")); print(d["mod"][sys.argv[1]])' "$1"; }
VERSION=$(toml version); MODID=$(toml id)
PROJECT="${CURSEFORGE_PROJECT_ID:-$(toml curseforge_project_id)}"
TOKEN="${CURSEFORGE_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$CRED_FILE" ]]; then TOKEN=$(sed -n 's/^TOKEN=//p' "$CRED_FILE" | tr -d '\r\n'); fi
[[ -n "$TOKEN" ]] || { echo "No CurseForge token (CURSEFORGE_TOKEN or $CRED_FILE)" >&2; exit 1; }

if [[ $BUILD -eq 1 ]]; then
  if [[ ${#ONLY[@]} -gt 0 ]]; then
    ./gradlew -q --console=plain $(printf ':%s:buildAndCollect ' "${ONLY[@]}")
  else
    ./gradlew -q --console=plain buildAll
  fi
fi

DIR="build/libs/$VERSION"
JARS=()
if [[ ${#ONLY[@]} -gt 0 ]]; then
  for n in "${ONLY[@]}"; do JARS+=("$DIR/$MODID-${n#*-}-$VERSION+${n%%-*}.jar"); done
else
  for j in "$DIR"/"$MODID"-*.jar; do JARS+=("$j"); done
fi

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
API=https://minecraft.curseforge.com/api
curl -sf -H "X-Api-Token: $TOKEN" "$API/game/version-types" -o "$TMP/types.json"
curl -sf -H "X-Api-Token: $TOKEN" "$API/game/versions" -o "$TMP/versions.json"
if [[ -z "$CHANGELOG" ]]; then
  printf '%s\n' "YAMC MCP ${VERSION}. See https://github.com/TargetedEntropy/ya-minecraft-mcp for setup and the full changelog." > "$TMP/changelog.md"
  CHANGELOG="$TMP/changelog.md"
fi

FAILED=0
for JAR in "${JARS[@]}"; do
  [[ -f "$JAR" ]] || { echo "missing $JAR" >&2; FAILED=1; continue; }
  # yamcmcp-<loader>-<mod version>+<minecraft>.jar
  base=$(basename "$JAR" .jar); rest=${base#"$MODID"-}; LOADER=${rest%%-*}; MC=${base##*+}
  # Game versions: the node's Minecraft releases (mod.mc_releases), the loader, Client, and the Java version.
  if ! python3 -I - "$TMP" "$MC" "$LOADER" "$VERSION" "$TYPE" "$CHANGELOG" > "$TMP/metadata.json" <<'PY'
import json, sys, tomllib
tmp, mc, loader, version, rtype, changelog = sys.argv[1:7]
props = tomllib.load(open("stonecutter.properties.toml", "rb"))
releases = props.get(mc, {}).get("mod", {}).get("mc_releases", [mc])
types = {t["id"]: t["slug"] for t in json.load(open(tmp + "/types.json"))}
versions = json.load(open(tmp + "/versions.json"))
def find(label, pred, required=True):
    for v in versions:
        if pred(v, types.get(v["gameVersionTypeID"], "")):
            return v["id"]
    if required:
        sys.exit(f"CurseForge game version not found: {label}")
    print(f"warning: CurseForge has no game version '{label}', skipping it", file=sys.stderr)
    return None
loader_name = {"fabric": "Fabric", "neoforge": "NeoForge"}[loader]
java = "Java 25" if int(mc.split(".")[0]) >= 26 else "Java 21"
ids = [find(r, lambda v, t, r=r: v["name"] == r and t.startswith("minecraft-")) for r in releases]
ids += [
    find(loader_name, lambda v, t: v["name"] == loader_name and t == "modloader"),
    find("Client", lambda v, t: v["name"] == "Client" and t == "environment"),
    find(java, lambda v, t: v["name"] == java and t == "java", required=False),
]
print(json.dumps({
    "changelog": open(changelog).read(), "changelogType": "markdown",
    "displayName": f"YAMC MCP {version} ({loader_name} {mc})",
    "gameVersions": [i for i in ids if i is not None], "releaseType": rtype,
}))
PY
  then FAILED=1; continue; fi

  echo "Project $PROJECT, file $JAR ($TYPE)"
  python3 -I -c 'import json,sys; m=json.load(open(sys.argv[1])); m["changelog"]=m["changelog"][:60]+"..."; print(json.dumps(m))' "$TMP/metadata.json"
  [[ $DRY -eq 1 ]] && continue
  HTTP=$(curl -s -o "$TMP/resp.json" -w "%{http_code}" -H "X-Api-Token: $TOKEN" \
    --form-string "metadata=$(cat "$TMP/metadata.json")" -F "file=@${JAR}" \
    "$API/projects/${PROJECT}/upload-file")
  echo "HTTP $HTTP: $(cat "$TMP/resp.json")"
  [[ "$HTTP" == 200 ]] || FAILED=1
done
[[ $DRY -eq 1 ]] && echo "dry run: nothing uploaded"
exit $FAILED
