#!/usr/bin/env bash
# Creates SecureHome's release signing key in ~/.securehome (outside the repo). Run once, then back up the folder.
# The password is random and goes straight into keystore.properties; it is never printed.
set -euo pipefail
DIR="${SECUREHOME_KEY_DIR:-$HOME/.securehome}"
KS="$DIR/release.jks"
PROPS="$DIR/keystore.properties"

if [[ -e "$KS" || -e "$PROPS" ]]; then
  echo "A release key already exists in $DIR. Not overwriting it." >&2
  exit 1
fi
mkdir -p "$DIR" && chmod 700 "$DIR"
umask 077

SECUREHOME_KS_PASS="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-40)"
export SECUREHOME_KS_PASS
keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias securehome \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=SecureHome" \
  -storepass:env SECUREHOME_KS_PASS -keypass:env SECUREHOME_KS_PASS >/dev/null 2>&1

cat > "$PROPS" <<PROPS
# SecureHome release signing. Keep this file and release.jks together, and back both up.
storeFile=$KS
storePassword=$SECUREHOME_KS_PASS
keyAlias=securehome
keyPassword=$SECUREHOME_KS_PASS
PROPS
unset SECUREHOME_KS_PASS

echo "Created $KS and $PROPS (both readable only by you)."
echo "Certificate SHA-256 (public, identifies SecureHome builds):"
keytool -list -v -keystore "$KS" -storepass:file <(grep '^storePassword=' "$PROPS" | cut -d= -f2-) 2>/dev/null \
  | grep -m1 'SHA256:' | sed 's/^[[:space:]]*/  /'
echo
echo "Back up $DIR now (for example as attachments in your password manager)."
echo "Without it you can't update SecureHome on the phones; you'd have to reinstall and pair everything again."
