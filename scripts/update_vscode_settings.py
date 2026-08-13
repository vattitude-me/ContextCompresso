#!/usr/bin/env python3
"""Merge ANTHROPIC_BASE_URL into a VS Code settings.json's terminal env blocks."""
import json
import sys

path, base_url = sys.argv[1], sys.argv[2]

with open(path) as f:
    settings = json.load(f)

for key in ("terminal.integrated.env.osx", "terminal.integrated.env.linux", "terminal.integrated.env.windows"):
    settings.setdefault(key, {})
    settings[key]["ANTHROPIC_BASE_URL"] = base_url

settings.setdefault("claudeCode.environmentVariables", {})
settings["claudeCode.environmentVariables"]["ANTHROPIC_BASE_URL"] = base_url

with open(path, "w") as f:
    json.dump(settings, f, indent=2)
    f.write("\n")
