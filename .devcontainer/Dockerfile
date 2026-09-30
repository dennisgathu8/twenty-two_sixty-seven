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

# Install Clojure CLI (pinned to 1.12.0.1488)
RUN curl -fsSL -O https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh \
    && chmod +x linux-install-${CLOJURE_VERSION}.sh \
    && ./linux-install-${CLOJURE_VERSION}.sh \
    && rm linux-install-${CLOJURE_VERSION}.sh

# Install Node.js (pinned to v22.14.0 LTS)
RUN ARCH=$(dpkg --print-architecture) \
    && case "$ARCH" in \
         amd64) NODE_ARCH="x64" ;; \
         arm64) NODE_ARCH="arm64" ;; \
         *) echo "Unsupported architecture: $ARCH" && exit 1 ;; \
       esac \
    && curl -fsSL -O "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" \
    && tar -xJf "node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz" -C /usr/local --strip-components=1 --no-same-owner \
    && rm "node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz"

# Install Caddy (pinned to v2.8.4 release deb; no external unpinned repo)
RUN ARCH=$(dpkg --print-architecture) \
    && curl -fsSL -O "https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/caddy_${CADDY_VERSION}_linux_${ARCH}.deb" \
    && dpkg -i "caddy_${CADDY_VERSION}_linux_${ARCH}.deb" \
    && rm "caddy_${CADDY_VERSION}_linux_${ARCH}.deb"

# Install Mailpit (pinned to v1.31.1 release binary; direct archive download without GitHub API rate limits)
RUN ARCH=$(dpkg --print-architecture) \
    && curl -fsSL -O "https://github.com/axllent/mailpit/releases/download/v${MAILPIT_VERSION}/mailpit-linux-${ARCH}.tar.gz" \
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
