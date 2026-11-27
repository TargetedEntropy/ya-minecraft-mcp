#!/usr/bin/env python3
"""Upload the built jars (every Minecraft version and loader) to CurseForge and/or Modrinth.

Usage: scripts/publish.py {curseforge,modrinth,all} [--type release|beta|alpha] [--changelog FILE] [--only NODE]...
                          [--no-build] [--dry-run]

NODE is a Stonecutter node such as 1.21.11-fabric; without --only, every node in stonecutter.properties.toml is
uploaded. Each jar is tagged with its Minecraft releases (mod.mc_releases in the loader's section, default: the version
it was built for), its loader, and (CurseForge) Client and its Java version.

CurseForge: token from CURSEFORGE_TOKEN or a file containing TOKEN=... (CURSEFORGE_CRED_FILE, default
  ~/.config/curseforge.cred); project id from CURSEFORGE_PROJECT_ID or mod.curseforge_project_id. Every run uploads new
  files: CurseForge has no way to tell what is already there.
Modrinth: personal access token from MODRINTH_TOKEN or a file containing PAT=... (MODRINTH_CRED_FILE, default
  ~/.config/modrinth.creds); project slug from MODRINTH_PROJECT or mod.modrinth_project. Creates the project if it does
  not exist and submits it for review; skips versions already uploaded, so re-running is safe.

Tokens are only ever sent in HTTP request headers: never printed, logged or passed on a command line.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tomllib
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "https://github.com/TargetedEntropy/ya-minecraft-mcp"
USER_AGENT = f"TargetedEntropy/ya-minecraft-mcp ({REPO})"
LOADER_NAMES = {"fabric": "Fabric", "neoforge": "NeoForge"}
FABRIC_API = "P7dR8mSH"  # Modrinth project id of Fabric API, which the Fabric jars require


@dataclass(frozen=True)
class Jar:
    node: str  # e.g. 1.21.11-fabric
    mc: str
    loader: str
    path: Path
    releases: list[str]  # Minecraft versions to tag

    @property
    def java(self) -> str:
        return "Java 25" if int(self.mc.split(".")[0]) >= 26 else "Java 21"

    def display_name(self, mod: dict) -> str:
        return f"{mod['name']} {mod['version']} ({LOADER_NAMES[self.loader]} {self.mc})"


def load_jars(props: dict, only: list[str]) -> list[Jar]:
    mod = props["mod"]
    jars = []
    for loader in LOADER_NAMES:
        for mc, section in props[loader].items():
            node = f"{mc}-{loader}"
            if only and node not in only:
                continue
            # The archive name set in build.<loader>.gradle.kts, collected by buildAndCollect.
            path = ROOT / "build" / "libs" / mod["version"] / f"{mod['id']}-{loader}-{mod['version']}+{mc}.jar"
            jars.append(Jar(node, mc, loader, path, section.get("mod", {}).get("mc_releases", [mc])))
    unknown = set(only) - {j.node for j in jars}
    if unknown:
        sys.exit(f"unknown node(s): {', '.join(sorted(unknown))}")
    return jars


def build(jars: list[Jar], everything: bool) -> None:
    tasks = ["buildAll"] if everything else [f":{j.node}:buildAndCollect" for j in jars]
    subprocess.run(["./gradlew", "-q", "--console=plain", *tasks], cwd=ROOT, check=True)


def read_token(env: str, cred_env: str, default_file: str, key: str) -> str:
    if os.environ.get(env):
        return os.environ[env]
    path = Path(os.environ.get(cred_env, Path.home() / default_file))
    if path.is_file():
        for line in path.read_text().splitlines():
            if line.startswith(key + "="):
                return line[len(key) + 1:].strip()
    sys.exit(f"No token: set {env} or put {key}=<token> in {path}")


class Http:
    """JSON-over-HTTP client with one auth header; non-2xx responses come back as (status, body), not exceptions."""

    def __init__(self, base: str, auth: dict[str, str]):
        self.base, self.auth = base, auth

    def request(self, method: str, path: str, *, data: bytes | None = None, content_type: str | None = None):
        headers = {"User-Agent": USER_AGENT, **self.auth}
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                status, body = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, body = e.code, e.read()
        try:
            return status, json.loads(body) if body else None
        except json.JSONDecodeError:
            return status, body.decode(errors="replace")

    def json(self, method: str, path: str, payload: dict):
        return self.request(method, path, data=json.dumps(payload).encode(), content_type="application/json")

    def multipart(self, path: str, fields: dict[str, str], files: dict[str, tuple[Path, str]]):
        boundary = uuid.uuid4().hex
        out = bytearray()
        for name, value in fields.items():
            out += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode() + value.encode() + b"\r\n"
        for name, (path_, ctype) in files.items():
            out += (f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{path_.name}"\r\n'
                    f"Content-Type: {ctype}\r\n\r\n").encode() + path_.read_bytes() + b"\r\n"
        out += f"--{boundary}--\r\n".encode()
        return self.request("POST", path, data=bytes(out), content_type=f"multipart/form-data; boundary={boundary}")


def curseforge(jars: list[Jar], mod: dict, args) -> bool:
    project = os.environ.get("CURSEFORGE_PROJECT_ID") or mod["curseforge_project_id"]
    api = Http("https://minecraft.curseforge.com/api",
               {"X-Api-Token": read_token("CURSEFORGE_TOKEN", "CURSEFORGE_CRED_FILE", ".config/curseforge.cred", "TOKEN")})
    status, types = api.request("GET", "/game/version-types")
    status2, versions = api.request("GET", "/game/versions")
    if status != 200 or status2 != 200:
        sys.exit(f"curseforge: cannot list game versions: HTTP {status}/{status2} (is the token valid?)")
    slugs = {t["id"]: t["slug"] for t in types}

    def version_id(name: str, kind) -> int | None:
        """The id of game version `name` whose type slug passes `kind`, or None."""
        return next((v["id"] for v in versions if v["name"] == name and kind(slugs.get(v["gameVersionTypeID"], ""))), None)

    ok = True
    for jar in jars:
        ids = [version_id(r, lambda t: t.startswith("minecraft-")) for r in jar.releases]
        ids += [version_id(LOADER_NAMES[jar.loader], lambda t: t == "modloader"), version_id("Client", lambda t: t == "environment")]
        if None in ids:
            print(f"{jar.node}: CurseForge does not know one of {jar.releases + [LOADER_NAMES[jar.loader], 'Client']}", file=sys.stderr)
            ok = False
            continue
        java = version_id(jar.java, lambda t: t == "java")
        meta = {"changelog": args.changelog_text, "changelogType": "markdown", "displayName": jar.display_name(mod),
                "gameVersions": ids + ([java] if java else []), "releaseType": args.type}
        print(f"curseforge {jar.node}: {json.dumps({k: v for k, v in meta.items() if k != 'changelog'})}")
        if args.dry_run:
            continue
        status, body = api.multipart(f"/projects/{project}/upload-file", {"metadata": json.dumps(meta)},
                                     {"file": (jar.path, "application/java-archive")})
        print(f"  HTTP {status}: {body}")
        ok &= status == 200
    return ok


def modrinth(jars: list[Jar], mod: dict, args) -> bool:
    slug = os.environ.get("MODRINTH_PROJECT") or mod["modrinth_project"]
    api = Http("https://api.modrinth.com/v2",
               {"Authorization": read_token("MODRINTH_TOKEN", "MODRINTH_CRED_FILE", ".config/modrinth.creds", "PAT")})

    status, project = api.request("GET", f"/project/{slug}")
    new_project = status == 404
    if new_project:
        print(f"modrinth: creating project {slug}")
        if not args.dry_run:
            project = create_modrinth_project(api, slug, mod)
    elif status != 200:
        sys.exit(f"modrinth: cannot read project {slug}: HTTP {status}: {project}")

    existing = set()
    if not args.dry_run or not new_project:
        _, versions = api.request("GET", f"/project/{slug}/version")
        existing = {v["version_number"] for v in versions}

    ok = True
    for jar in jars:
        number = f"{mod['version']}+{jar.mc}-{jar.loader}"
        if number in existing:
            print(f"modrinth {jar.node}: {number} already uploaded, skipping")
            continue
        data = {"name": jar.display_name(mod), "version_number": number, "changelog": args.changelog_text,
                "dependencies": [{"project_id": FABRIC_API, "dependency_type": "required"}] if jar.loader == "fabric" else [],
                "game_versions": jar.releases, "version_type": args.type, "loaders": [jar.loader], "featured": False,
                "status": "listed", "project_id": project["id"] if project else slug, "file_parts": ["file"], "primary_file": "file"}
        print(f"modrinth {jar.node}: {json.dumps({k: v for k, v in data.items() if k != 'changelog'})}")
        if args.dry_run:
            continue
        status, body = api.multipart("/version", {"data": json.dumps(data)}, {"file": (jar.path, "application/java-archive")})
        print(f"  HTTP {status}: {body['id'] if status == 200 else body}")
        ok &= status == 200

    if new_project and ok and not args.dry_run:
        # A new project starts as a draft; submit it for Modrinth's review once its versions are up.
        status, body = api.json("PATCH", f"/project/{slug}", {"status": "processing"})
        print(f"modrinth: submitted {slug} for review" if status == 204 else f"modrinth: submit for review: HTTP {status}: {body}")
        ok &= status == 204
    return ok


def create_modrinth_project(api: Http, slug: str, mod: dict) -> dict:
    def absolute(m: re.Match) -> str:  # README links relative to the repo, pointed at GitHub
        target = m.group(1)
        if "://" in target:
            return m.group(0)
        return f"]({REPO}#{target[1:]})" if target.startswith("#") else f"]({REPO}/blob/master/{target})"

    data = {"slug": slug, "title": mod["name"], "project_type": "mod",
            "description": "Client-side mod that runs an MCP server in Minecraft, so AI assistants can read and act on live game state.",
            "body": re.sub(r"\]\(([^)]+)\)", absolute, (ROOT / "README.md").read_text()),
            "categories": ["utility"], "additional_categories": ["management"],
            "client_side": "required", "server_side": "unsupported", "license_id": mod["license"],
            "source_url": REPO, "issues_url": REPO + "/issues", "wiki_url": REPO + "#readme",
            "is_draft": True, "initial_versions": []}
    status, project = api.multipart("/project", {"data": json.dumps(data)}, {"icon": (ROOT / "logo.png", "image/png")})
    if status != 200:
        sys.exit(f"modrinth: create project: HTTP {status}: {project}")
    # Creation ignores client_side/server_side, and review requires them: set them afterwards.
    status, body = api.json("PATCH", f"/project/{slug}", {"client_side": "required", "server_side": "unsupported"})
    if status != 204:
        sys.exit(f"modrinth: set environment: HTTP {status}: {body}")
    return project


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("site", choices=["curseforge", "modrinth", "all"])
    p.add_argument("--type", choices=["release", "beta", "alpha"], default="release")
    p.add_argument("--changelog", type=Path, help="Markdown file (default: a line pointing at the GitHub repo)")
    p.add_argument("--only", action="append", default=[], metavar="NODE")
    p.add_argument("--no-build", dest="build", action="store_false")
    p.add_argument("--dry-run", action="store_true", help="show what would be uploaded; Modrinth reads are still made")
    args = p.parse_args()

    props = tomllib.loads((ROOT / "stonecutter.properties.toml").read_text())
    mod = props["mod"]
    args.changelog_text = (args.changelog.read_text() if args.changelog
                           else f"{mod['name']} {mod['version']}. See {REPO} for setup and the full changelog.\n")
    jars = load_jars(props, args.only)
    if args.build:
        build(jars, everything=not args.only)
    missing = [str(j.path.relative_to(ROOT)) for j in jars if not j.path.is_file()]
    if missing:
        sys.exit("missing jars (build first, or drop --no-build):\n  " + "\n  ".join(missing))

    ok = True
    if args.site in ("curseforge", "all"):
        ok &= curseforge(jars, mod, args)
    if args.site in ("modrinth", "all"):
        ok &= modrinth(jars, mod, args)
    if args.dry_run:
        print("dry run: nothing uploaded")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
