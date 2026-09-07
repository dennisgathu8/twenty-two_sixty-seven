# syntax=docker/dockerfile:1
FROM debian:bookworm-slim

# Explicitly pin versions as required by project governance (§4, §12)
ENV JAVA_VERSION=17 \
    CLOJURE_VERSION=1.12.0.1488 \
    CADDY_VERSION=2.8.4 \
    MAILPIT_VERSION=1.31.1 \
    DEBIAN_FRONTEND=noninteractive

# System dependencies from Debian 12 Bookworm
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
    && rm -rf /var/lib/apt/lists/*

# Install Clojure CLI (pinned to 1.12.0.1488)
RUN curl -fsSL -O https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh \
    && chmod +x linux-install-${CLOJURE_VERSION}.sh \
    && ./linux-install-${CLOJURE_VERSION}.sh \
    && rm linux-install-${CLOJURE_VERSION}.sh

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
