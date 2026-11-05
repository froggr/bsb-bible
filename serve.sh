#!/bin/sh
# Serve the current build and data layers to the phone.
cd "$(dirname "$0")"
exec python3 serve.py "${1:-8099}"
