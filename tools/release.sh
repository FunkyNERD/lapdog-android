#!/bin/sh
# Cuts an app release: sets versionName (and bumps versionCode) in app/build.gradle.kts, commits
# it, tags the commit vX.Y.Z and pushes both. The tag starts .forgejo/workflows/release.yml,
# which builds the signed APK and publishes the release the website's download page offers.
#
#   tools/release.sh 1.2.3            # a release
#   tools/release.sh 1.2.3-beta.1     # a prerelease (published, but not offered by the website)
set -eu

VERSION="${1:-}"
VERSION="${VERSION#v}"
echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' || {
    echo "usage: $0 MAJOR.MINOR.PATCH[-suffix]" >&2
    exit 1
}
TAG="v$VERSION"
GRADLE=app/build.gradle.kts
cd "$(dirname "$0")/.."

[ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || { echo "Release from main." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Commit or stash your changes first." >&2; exit 1; }
git fetch -q origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main isn't in step with origin/main - pull/push first." >&2; exit 1; }
! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || { echo "$TAG already exists." >&2; exit 1; }

# Android only installs an update with a higher versionCode, so every release bumps it.
CODE=$(sed -nE 's/^ *versionCode = ([0-9]+)$/\1/p' "$GRADLE")
[ -n "$CODE" ] || { echo "Couldn't find versionCode in $GRADLE" >&2; exit 1; }
CODE=$((CODE + 1))
sed -i.bak -E "s/^( *versionCode = )[0-9]+$/\\1$CODE/; s/^( *versionName = )\"[^\"]*\"$/\\1\"$VERSION\"/" "$GRADLE" && rm -f "$GRADLE.bak"
grep -Eq "^ *versionName = \"$VERSION\"$" "$GRADLE" || { echo "Couldn't set versionName in $GRADLE" >&2; exit 1; }

git commit -q -m "chore(release): $TAG" "$GRADLE"
git tag -a "$TAG" -m "LapDog app $VERSION"
git push -q origin main "$TAG"
echo "Released $TAG (versionCode $CODE) - the release workflow is building it now."
