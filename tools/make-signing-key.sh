#!/bin/sh
# Makes the LapDog release signing key and, with FORGEJO_TOKEN set, stores it as the repo's
# Actions secrets the release workflow signs with. Run it once.
#
#   FORGEJO_TOKEN=<token with write:repository> tools/make-signing-key.sh
#
# The key lives in ~/.config/lapdog-android/ (release.jks and its password). BACK BOTH UP: every
# later release must be signed with the same key, or Android refuses it as an update and pilots
# have to uninstall the app (and lose its settings) to install it.
#
# Without FORGEJO_TOKEN it only makes the key; add the secrets by hand under the repo's
# Settings > Actions > Secrets:
#
#   LAPDOG_KEYSTORE_B64        base64 -w0 ~/.config/lapdog-android/release.jks
#   LAPDOG_KEYSTORE_PASSWORD   the contents of ~/.config/lapdog-android/keystore-password
set -eu

DIR="${LAPDOG_KEY_DIR:-$HOME/.config/lapdog-android}"
KEYSTORE="$DIR/release.jks"
PASSFILE="$DIR/keystore-password"
REPO="${FORGEJO_REPO:-jamie/lapdog-android}"
FORGEJO_URL="${FORGEJO_URL:-https://git.hazaar.dev}"

mkdir -p "$DIR"
chmod 700 "$DIR"
if [ -e "$KEYSTORE" ]; then
    echo "Using the existing key in $KEYSTORE."
else
    umask 077
    openssl rand -hex 24 > "$PASSFILE"
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias lapdog \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -dname "CN=LapDog, O=Funky Nerd" \
        -storepass:file "$PASSFILE" >/dev/null
    echo "Made a new release key in $KEYSTORE. Back up that folder somewhere safe."
fi

if [ -z "${FORGEJO_TOKEN:-}" ]; then
    echo "FORGEJO_TOKEN isn't set, so the secrets weren't stored. See the top of this script to add them by hand."
    exit 0
fi

put_secret() {
    # $1 secret name; the value comes in on stdin, so it never appears in the process list.
    jq -Rs '{data: .}' | curl -fsS -X PUT -H "Authorization: token $FORGEJO_TOKEN" \
        -H "Content-Type: application/json" --data-binary @- \
        "$FORGEJO_URL/api/v1/repos/$REPO/actions/secrets/$1" >/dev/null
    echo "Stored $1 on $REPO."
}
base64 -w0 "$KEYSTORE" | put_secret LAPDOG_KEYSTORE_B64
tr -d '\n' < "$PASSFILE" | put_secret LAPDOG_KEYSTORE_PASSWORD
