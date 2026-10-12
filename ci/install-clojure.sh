#!/usr/bin/env bash
set -euo pipefail

# Installs Clojure CLI with verified SHA-256 checksum matching .devcontainer/Dockerfile (§4, §11).

CLOJURE_VERSION="1.12.0.1488"
CLOJURE_INSTALLER_SHA256="5f679e004acd3354c5b28d76703d14b96eb9b6d839eaca4f54b13b90b764682e"

INSTALLER_SCRIPT="/tmp/linux-install-${CLOJURE_VERSION}.sh"

echo "Downloading Clojure CLI ${CLOJURE_VERSION}..."
curl -fsSL -o "${INSTALLER_SCRIPT}" "https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh"

echo "Verifying SHA-256 checksum..."
echo "${CLOJURE_INSTALLER_SHA256}  ${INSTALLER_SCRIPT}" | sha256sum -c -

echo "Installing Clojure CLI..."
chmod +x "${INSTALLER_SCRIPT}"
sudo "${INSTALLER_SCRIPT}"
rm -f "${INSTALLER_SCRIPT}"

echo "Clojure CLI installed successfully:"
clojure --version
