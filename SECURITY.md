# Security policy

YAMC MCP runs an HTTP server inside the Minecraft client that can read game state and, with actions enabled, act as the
player. Bugs that let anything other than the intended local MCP client reach or control it are security issues: token
bypass, binding beyond loopback, cross-origin or DNS-rebinding access, reading files outside the game directory, and so on.

## Reporting

Please report privately through
[GitHub's vulnerability reporting](https://github.com/TargetedEntropy/ya-minecraft-mcp/security/advisories/new),
not in a public issue. Include the mod version, Minecraft version, loader, and steps to reproduce.

## Supported versions

Fixes go into the latest release only.
