# syntax=docker/dockerfile:1
FROM debian:bookworm-slim

# Explicitly pin JDK 17 as required by project governance
ENV JAVA_VERSION=17 \
    CLOJURE_VERSION=1.12.0.1488 \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    curl \
    git \
    gnupg \
    ca-certificates \
    apt-transport-https \
    debian-keyring \
    debian-archive-keyring \
    libnss3-tools \
    procps \
    rlwrap \
    sudo \
    && rm -rf /var/lib/apt/lists/*

# Install Clojure CLI
RUN curl -fsSL -O https://download.clojure.org/install/linux-install-${CLOJURE_VERSION}.sh \
    && chmod +x linux-install-${CLOJURE_VERSION}.sh \
    && ./linux-install-${CLOJURE_VERSION}.sh \
    && rm linux-install-${CLOJURE_VERSION}.sh

# Install Caddy
RUN curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg \
    && curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list \
    && apt-get update && apt-get install -y caddy \
    && rm -rf /var/lib/apt/lists/*

# Install Mailpit
RUN curl -sL https://raw.githubusercontent.com/axllent/mailpit/develop/install.sh | bash

# Create non-root developer user
RUN useradd -m -s /bin/bash -u 1000 developer \
    && echo "developer ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

USER developer
WORKDIR /workspace

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

CMD ["/bin/bash"]
