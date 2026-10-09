#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REVISION=b7b10f33c2890f1bbbd5caace6c8a652b4a58961
DEST="$ROOT/vendor/hyperborea"
PATCH="$ROOT/patches/hyperborea-rower.patch"

if [[ -d "$DEST" ]]; then
    echo "Ignored Hyperborea sources already exist at $DEST. Leaving them unchanged."
    exit 0
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/nordicrower-vendor.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
if [[ $# -gt 0 ]]; then
    UPSTREAM="$1"
    [[ "$(git -C "$UPSTREAM" rev-parse HEAD)" == "$REVISION" ]] || {
        echo "Local upstream must be at $REVISION" >&2; exit 1;
    }
    [[ -z "$(git -C "$UPSTREAM" status --porcelain)" ]] || {
        echo "Local upstream must have a clean working tree" >&2; exit 1;
    }
else
    git init -q "$WORK/upstream"
    git -C "$WORK/upstream" remote add origin https://github.com/ball2jh/Hyperborea.git
    git -C "$WORK/upstream" fetch --depth 1 origin "$REVISION"
    git -C "$WORK/upstream" checkout -q --detach FETCH_HEAD
    UPSTREAM="$WORK/upstream"
fi

STAGE="$WORK/hyperborea"
CORE="$STAGE/src/com/nettarion/hyperborea/core"
FITPRO="$STAGE/src/com/nettarion/hyperborea/hardware/fitpro"
mkdir -p "$CORE/model" "$FITPRO" "$STAGE/resources"
cp "$UPSTREAM/core/src/main/kotlin/com/nettarion/hyperborea/core/Logging.kt" "$CORE/"
for name in DeviceCommand DeviceInfo DeviceIdentity DeviceCapabilities ExerciseData; do
    cp "$UPSTREAM/core/src/main/kotlin/com/nettarion/hyperborea/core/model/$name.kt" "$CORE/model/"
done
for name in session v1 v2 transport; do
    cp -R "$UPSTREAM/hardware/fitpro/src/main/java/com/nettarion/hyperborea/hardware/fitpro/$name" "$FITPRO/"
done
cp "$UPSTREAM/hardware/fitpro/src/main/resources/ifit_device_catalog.tsv" "$STAGE/resources/"
cp "$UPSTREAM/LICENSE" "$STAGE/LICENSE"
git -C "$STAGE" apply "$PATCH"
printf '%s\n' "$REVISION" > "$STAGE/SOURCE_REVISION"
mkdir -p "$ROOT/vendor"
mv "$STAGE" "$DEST"
echo "Prepared ignored Hyperborea dependency at $REVISION with NordicRower patch."
