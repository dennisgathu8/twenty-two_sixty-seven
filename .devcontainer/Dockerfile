# syntax=docker/dockerfile:1
FROM debian:bookworm-slim

# Explicitly pin versions as required by project governance (§4, §12)
ENV JAVA_VERSION=17 \
    CLOJURE_VERSION=1.12.0.1488 \
    CADDY_VERSION=2.8.4 \
    MAILPIT_VERSION=1.31.1 \
    NODE_VERSION=22.14.0 \
    DEBIAN_FRONTEND=noninteractive

# System dependencies from Debian 12 Bookworm + Chromium headless dependencies (§10)
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    curl \
    git \
    ca-certificates \
    libnss3-tools \
    procps \
    rlwrap \
    sudo \
    tar \
    xz-utils \
    fonts-liberation \
    libasound2 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libcairo2 \
    libcups2 \
    libdbus-1-3 \
    libdrm2 \
    libgbm1 \
    libglib2.0-0 \
    libgtk-3-0 \
    libnspr4 \
    libnss3 \
    libpango-1.0-0 \
    libx11-6 \
    libx11-xcb1 \
    libxcb1 \
    libxcomposite1 \
    libxcursor1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxi6 \
    libxkbcommon0 \
    libxrandr2 \
    libxrender1 \
    libxshmfence1 \
    libxtst6 \
    && rm -rf /var/lib/apt/lists/*

# ==============================================================================
# Pinned SHA-256 checksums for verified artifact downloads (§4, §9, §12)
# Declared below the apt layer to strictly preserve its cached build layer.
#
# How to bump a pin:
#   1. Bump the VERSION variable in the top ENV block (above apt).
#   2. Look up the upstream published SHA-256 for the new version (or compute
#      and document TOFU if none published).
#   3. Update the corresponding SHA256 variable(s) below.
# ==============================================================================

# Clojure CLI 1.12.0.1488: Trust-on-first-use (TOFU). No upstream checksum published; computed from download URL.
ENV CLOJURE_INSTALLER_SHA256="5f679e004acd3354c5b28d76703d14b96eb9b6d839eaca4f54b13b90b764682e" \
    # Node.js 22.14.0 LTS: Sourced from official https://nodejs.org/dist/v22.14.0/SHASUMS256.txt
    NODE_SHA256_X64="69b09dba5c8dcb05c4e4273a4340db1005abeafe3927efda2bc5b249e80437ec" \
    NODE_SHA256_ARM64="08bfbf538bad0e8cbb0269f0173cca28d705874a67a22f60b57d99dc99e30050" \
    # Caddy 2.8.4: Computed from official GitHub release deb assets; verified against upstream caddy_2.8.4_checksums.txt (SHA-512)
    CADDY_SHA256_AMD64="8ea1c5dd7736307e4b4baab2a3ffe51f28f4e344536cbc32f3e4bf12e65d79cd" \
    CADDY_SHA256_ARM64="8ada2c4ef24feeb1eb899fd31a50e73e13813bc993d1149f4ab4bc07ceda5a90" \
    # Mailpit 1.31.1: Sourced from official GitHub release asset metadata digests (v1.31.1 API)
    MAILPIT_SHA256_AMD64="87d2652abd7c17dc99029147ff62ac671afdf7fd76630f7faf5b14125211c709" \
    MAILPIT_SHA256_ARM64="be6a1f9dcf0ac0d7157ee777eac2b5351352b367bcae3c0a0f4854da60546884"

# Install Clojure CLI (pinned to 1.12.0.1488 with SHA-256 verification)
RUN curl -fsSL -O "https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh" \
    && echo "${CLOJURE_INSTALLER_SHA256}  linux-install-${CLOJURE_VERSION}.sh" | sha256sum -c - \
    && chmod +x "linux-install-${CLOJURE_VERSION}.sh" \
    && ./"linux-install-${CLOJURE_VERSION}.sh" \
    && rm "linux-install-${CLOJURE_VERSION}.sh"

# Install Node.js (pinned to v22.14.0 LTS with SHA-256 verification)
RUN ARCH=$(dpkg --print-architecture) \
    && case "$ARCH" in \
         amd64) NODE_ARCH="x64" NODE_SHA256="${NODE_SHA256_X64}" ;; \
         arm64) NODE_ARCH="arm64" NODE_SHA256="${NODE_SHA256_ARM64}" ;; \
         *) echo "Unsupported architecture: $ARCH" && exit 1 ;; \
       esac \
    && curl -fsSL -O "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" \
    && echo "${NODE_SHA256}  node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" | sha256sum -c - \
    && tar -xJf "node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" -C /usr/local --strip-components=1 --no-same-owner \
    && rm "node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz"

# Install Caddy (pinned to v2.8.4 release deb with SHA-256 verification)
RUN ARCH=$(dpkg --print-architecture) \
    && case "$ARCH" in \
         amd64) CADDY_SHA256="${CADDY_SHA256_AMD64}" ;; \
         arm64) CADDY_SHA256="${CADDY_SHA256_ARM64}" ;; \
         *) echo "Unsupported architecture: $ARCH" && exit 1 ;; \
       esac \
    && curl -fsSL -O "https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/caddy_${CADDY_VERSION}_linux_${ARCH}.deb" \
    && echo "${CADDY_SHA256}  caddy_${CADDY_VERSION}_linux_${ARCH}.deb" | sha256sum -c - \
    && dpkg -i "caddy_${CADDY_VERSION}_linux_${ARCH}.deb" \
    && rm "caddy_${CADDY_VERSION}_linux_${ARCH}.deb"

# Install Mailpit (pinned to v1.31.1 release binary with SHA-256 verification)
RUN ARCH=$(dpkg --print-architecture) \
    && case "$ARCH" in \
         amd64) MAILPIT_SHA256="${MAILPIT_SHA256_AMD64}" ;; \
         arm64) MAILPIT_SHA256="${MAILPIT_SHA256_ARM64}" ;; \
         *) echo "Unsupported architecture: $ARCH" && exit 1 ;; \
       esac \
    && curl -fsSL -O "https://github.com/axllent/mailpit/releases/download/v${MAILPIT_VERSION}/mailpit-linux-${ARCH}.tar.gz" \
    && echo "${MAILPIT_SHA256}  mailpit-linux-${ARCH}.tar.gz" | sha256sum -c - \
    && tar -xzf "mailpit-linux-${ARCH}.tar.gz" -C /usr/local/bin mailpit \
    && rm "mailpit-linux-${ARCH}.tar.gz" \
    && chmod +x /usr/local/bin/mailpit

# Create non-root developer user (UID 1000) and initialize workspace directory permissions
RUN useradd -m -s /bin/bash -u 1000 developer \
    && echo "developer ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers \
    && mkdir -p /workspace \
    && chown -R developer:developer /workspace

USER developer
WORKDIR /workspace

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

CMD ["/bin/bash"]
