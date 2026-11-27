#!/usr/bin/env bash
# Upload the built jars (every Minecraft version and loader) to Modrinth, creating the project first if it does not exist.
#
# Usage: scripts/publish-modrinth.sh [--type release|beta|alpha (default release)] [--changelog FILE] [--only NODE]... [--no-build] [--dry-run]
#   NODE is a Stonecutter node such as 1.21.11-fabric. Without --only, every jar in build/libs/<mod version>/ is uploaded.
#   Versions already on Modrinth (same version number) are skipped, so re-running is safe.
#
# Needs:
#   - Modrinth personal access token (scopes: create projects, create versions, write projects): env MODRINTH_TOKEN, or a
#     file (default ~/.config/modrinth.creds) containing PAT=<token>. Create one at https://modrinth.com/settings/pats
#   - Project slug: env MODRINTH_PROJECT, or mod.modrinth_project in stonecutter.properties.toml
#   - Python 3.11+ (tomllib)
# The token is never printed, and is passed to curl through a private header file rather than the command line.
set -euo pipefail
cd "$(dirname "$0")/.."

TYPE=release; CHANGELOG=""; DRY=0; BUILD=1; ONLY=()
CRED_FILE="${MODRINTH_CRED_FILE:-$HOME/.config/modrinth.creds}"
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
PROJECT="${MODRINTH_PROJECT:-$(toml modrinth_project)}"
TOKEN="${MODRINTH_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$CRED_FILE" ]]; then TOKEN=$(sed -n 's/^PAT=//p' "$CRED_FILE" | tr -d '\r\n'); fi
[[ -n "$TOKEN" ]] || { echo "No Modrinth token (MODRINTH_TOKEN or $CRED_FILE)" >&2; exit 1; }

REPO=https://github.com/TargetedEntropy/ya-minecraft-mcp
API=https://api.modrinth.com/v2
UA="TargetedEntropy/ya-minecraft-mcp ($REPO)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
( umask 077; printf 'Authorization: %s\n' "$TOKEN" > "$TMP/auth" ); unset TOKEN
api() { curl -s -A "$UA" -H @"$TMP/auth" -o "$TMP/resp.json" -w "%{http_code}" "$@"; }

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

# Create the project (as a draft) if it does not exist yet. The README becomes the description page, with relative links
# pointed at GitHub.
NEW_PROJECT=0
if [[ "$(api "$API/project/$PROJECT")" == 404 ]]; then
  NEW_PROJECT=1
  python3 -I - "$PROJECT" "$REPO" > "$TMP/project.json" <<'PY'
import json, re, sys, tomllib
slug, repo = sys.argv[1:3]
mod = tomllib.load(open("stonecutter.properties.toml", "rb"))["mod"]
def absolute(m):
    target = m.group(1)
    if "://" in target: return m.group(0)
    if target.startswith("#"): return f"]({repo}#{target[1:]})"
    return f"]({repo}/blob/master/{target})"
body = re.sub(r"\]\(([^)]+)\)", absolute, open("README.md").read())
print(json.dumps({
    "slug": slug, "title": mod["name"], "project_type": "mod",
    "description": "Client-side mod that runs an MCP server in Minecraft, so AI assistants can read and act on live game state.",
    "body": body, "categories": ["utility"], "additional_categories": ["management"],
    "client_side": "required", "server_side": "unsupported", "license_id": mod["license"],
    "source_url": repo, "issues_url": repo + "/issues", "wiki_url": repo + "#readme",
    "is_draft": True, "initial_versions": [],
}))
PY
  echo "Creating Modrinth project $PROJECT"
  if [[ $DRY -eq 0 ]]; then
    HTTP=$(api -X POST --form-string "data=$(cat "$TMP/project.json")" -F "icon=@logo.png;type=image/png" "$API/project")
    [[ "$HTTP" == 200 ]] || { echo "HTTP $HTTP: $(cat "$TMP/resp.json")" >&2; exit 1; }
    # Creation ignores client_side/server_side, and review requires them: set them afterwards.
    HTTP=$(api -X PATCH -H 'Content-Type: application/json' -d '{"client_side":"required","server_side":"unsupported"}' "$API/project/$PROJECT")
    [[ "$HTTP" == 204 ]] || { echo "set environment: HTTP $HTTP: $(cat "$TMP/resp.json")" >&2; exit 1; }
  fi
fi

PROJECT_ID=$PROJECT
EXISTING="[]"
if [[ $DRY -eq 0 || $NEW_PROJECT -eq 0 ]]; then
  [[ "$(api "$API/project/$PROJECT")" == 200 ]] || { echo "cannot read project $PROJECT: $(cat "$TMP/resp.json")" >&2; exit 1; }
  PROJECT_ID=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$TMP/resp.json")
  api "$API/project/$PROJECT/version" >/dev/null
  EXISTING=$(python3 -I -c 'import json,sys; print(json.dumps([v["version_number"] for v in json.load(open(sys.argv[1]))]))' "$TMP/resp.json")
fi

if [[ -z "$CHANGELOG" ]]; then
  printf '%s\n' "YAMC MCP ${VERSION}. See $REPO for setup and the full changelog." > "$TMP/changelog.md"
  CHANGELOG="$TMP/changelog.md"
fi

FAILED=0
for JAR in "${JARS[@]}"; do
  [[ -f "$JAR" ]] || { echo "missing $JAR" >&2; FAILED=1; continue; }
  # yamcmcp-<loader>-<mod version>+<minecraft>.jar
  base=$(basename "$JAR" .jar); rest=${base#"$MODID"-}; LOADER=${rest%%-*}; MC=${base##*+}
  NUMBER="$VERSION+$MC-$LOADER"
  if python3 -I -c 'import json,sys; sys.exit(0 if sys.argv[2] in json.loads(sys.argv[1]) else 1)' "$EXISTING" "$NUMBER"; then
    echo "skip $NUMBER (already on Modrinth)"; continue
  fi
  python3 -I - "$MC" "$LOADER" "$VERSION" "$NUMBER" "$TYPE" "$CHANGELOG" "$PROJECT_ID" > "$TMP/version.json" <<'PY'
import json, sys, tomllib
mc, loader, version, number, rtype, changelog, project_id = sys.argv[1:8]
props = tomllib.load(open("stonecutter.properties.toml", "rb"))
releases = props.get(loader, {}).get(mc, {}).get("mod", {}).get("mc_releases", [mc])
deps = [{"project_id": "P7dR8mSH", "dependency_type": "required"}] if loader == "fabric" else []  # Fabric API
name = {"fabric": "Fabric", "neoforge": "NeoForge"}[loader]
print(json.dumps({
    "name": f"YAMC MCP {version} ({name} {mc})", "version_number": number, "changelog": open(changelog).read(),
    "dependencies": deps, "game_versions": releases, "version_type": rtype, "loaders": [loader],
    "featured": False, "status": "listed", "project_id": project_id, "file_parts": ["file"], "primary_file": "file",
}))
PY
  echo "$JAR -> $(python3 -I -c 'import json,sys; m=json.load(open(sys.argv[1])); m.pop("changelog"); print(json.dumps(m))' "$TMP/version.json")"
  [[ $DRY -eq 1 ]] && continue
  HTTP=$(api -X POST --form-string "data=$(cat "$TMP/version.json")" -F "file=@${JAR};type=application/java-archive" "$API/version")
  if [[ "$HTTP" == 200 ]]; then echo "HTTP 200: version $(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$TMP/resp.json")"
  else echo "HTTP $HTTP: $(cat "$TMP/resp.json")"; FAILED=1; fi
done

# A new project starts as a draft; submit it for Modrinth's review once its versions are up.
if [[ $DRY -eq 0 && $NEW_PROJECT -eq 1 && $FAILED -eq 0 ]]; then
  HTTP=$(api -X PATCH -H 'Content-Type: application/json' -d '{"status":"processing"}' "$API/project/$PROJECT")
  if [[ "$HTTP" == 204 ]]; then echo "Submitted $PROJECT for review"; else echo "submit for review: HTTP $HTTP: $(cat "$TMP/resp.json")"; FAILED=1; fi
fi
[[ $DRY -eq 1 ]] && echo "dry run: nothing uploaded"
exit $FAILED
