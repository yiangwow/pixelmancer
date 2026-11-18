#!/bin/bash
# Launcher for PIXELMANCER — resolves the JDK without needing an interactive shell,
# so it works from a bare `wsl.exe -e` session (Windows desktop shortcut).
cd "$(dirname "$0")" || exit 1

JAVA=$(ls -d "$HOME"/.local/jdk/*/bin/java 2>/dev/null | sort -V | tail -1)
[ -x "$JAVA" ] || JAVA=$(command -v java)
if [ -z "$JAVA" ]; then
    echo "PIXELMANCER: no java found" >&2
    exit 1
fi

[ -f Pixelmancer.class ] || "${JAVA%java}javac" Pixelmancer.java || exit 1
exec "$JAVA" Pixelmancer
