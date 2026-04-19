# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# syntax=docker/dockerfile:1.4
FROM teaclave/teaclave-java-tee-sdk:v0.1.0-ubuntu18.04

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ARG REMOTE_USER=dev
ARG REMOTE_GROUP=${REMOTE_USER}
ARG REMOTE_UID=1010
ARG REMOTE_GID=${REMOTE_UID}
ARG REMOTE_HOME=/home/${REMOTE_USER}

ARG GIT_EMAIL=your_name@your_domain.com
ARG GIT_NAME="your name"

# Settings for additional tools
ARG GRAALVM_VERSION=22.2.0
ARG GRAALVM_ARCH=amd64
ARG OPENJDK_VERSION=17
ARG MAVEN_VERSION=3.9.12

ENV DEBIAN_FRONTEND=noninteractive \
    DEVCONTAINER_USER=${REMOTE_USER} \
    REMOTE_USER=${REMOTE_USER} \
    REMOTE_HOME=${REMOTE_HOME} \
    GIT_NAME=${GIT_NAME} \
    GIT_EMAIL=${GIT_EMAIL} \
    GRAALVM_HOME=/root/tools/graalvm-ce-java17-${GRAALVM_VERSION} \
    JAVA_HOME=/root/tools/graalvm-ce-java17-${GRAALVM_VERSION}

RUN apt-get update \
    && apt-get install -y --no-install-recommends software-properties-common \
    && add-apt-repository universe \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
    # Build & toolchain
    autoconf automake build-essential cmake g++ gcc make zlib1g-dev \
    \
    # VCS
    git \
    \
    # Networking & crypto
    ca-certificates curl openssh-client openssh-server \
    openssl \
    \
    # Archiving & compression
    tar unzip xz-utils \
    \
    # Shells & terminal
    sudo tmux zsh \
    # Newer Java version
    openjdk-${OPENJDK_VERSION}-jdk \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user with passwordless sudo
RUN GROUP_NAME="${REMOTE_GROUP}" \
    && EXISTING_GID_GROUP="$(getent group "${REMOTE_GID}" | cut -d: -f1 || true)" \
    && if [ -n "${EXISTING_GID_GROUP}" ]; then \
    GROUP_NAME="${EXISTING_GID_GROUP}"; \
    elif getent group "${REMOTE_GROUP}" >/dev/null; then \
    groupmod --gid "${REMOTE_GID}" "${REMOTE_GROUP}"; \
    else \
    groupadd --gid "${REMOTE_GID}" "${REMOTE_GROUP}"; \
    fi \
    && EXISTING_UID_USER="$(getent passwd "${REMOTE_UID}" | cut -d: -f1 || true)" \
    && if ! id -u "${REMOTE_USER}" >/dev/null 2>&1 \
    && [ -n "${EXISTING_UID_USER}" ] \
    && [ "${EXISTING_UID_USER}" != "${REMOTE_USER}" ]; then \
    usermod --login "${REMOTE_USER}" "${EXISTING_UID_USER}"; \
    fi \
    && if id -u "${REMOTE_USER}" >/dev/null 2>&1; then \
    usermod --gid "${GROUP_NAME}" --shell /usr/bin/zsh "${REMOTE_USER}"; \
    CURRENT_UID="$(id -u "${REMOTE_USER}")"; \
    if [ "${CURRENT_UID}" -ne "${REMOTE_UID}" ] \
    && { [ -z "${EXISTING_UID_USER}" ] || [ "${EXISTING_UID_USER}" = "${REMOTE_USER}" ]; }; then \
    usermod --uid "${REMOTE_UID}" "${REMOTE_USER}"; \
    fi; \
    CURRENT_HOME="$(getent passwd "${REMOTE_USER}" | cut -d: -f6)"; \
    if [ "${CURRENT_HOME}" != "${REMOTE_HOME}" ]; then \
    usermod --home "${REMOTE_HOME}" --move-home "${REMOTE_USER}"; \
    fi; \
    else \
    useradd --create-home --shell /usr/bin/zsh --uid "${REMOTE_UID}" --gid "${GROUP_NAME}" "${REMOTE_USER}"; \
    fi \
    && usermod -aG sudo "${REMOTE_USER}" \
    && echo "${REMOTE_USER} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/90-"${REMOTE_USER}" \
    && chmod 0440 /etc/sudoers.d/90-"${REMOTE_USER}" \
    && echo "${GROUP_NAME}" > /etc/devcontainer-group

# Update owner of image built-in toolings
RUN chown -R "${REMOTE_USER}:${GROUP_NAME}" /root

# SSH server configuration
RUN mkdir -p /var/run/sshd \
    && ssh-keygen -A \
    && sed -i 's/^#\?Port .*/Port 2222/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PubkeyAuthentication .*/PubkeyAuthentication no/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?PermitRootLogin .*/PermitRootLogin no/' /etc/ssh/sshd_config \
    && sed -i 's/^#\?UsePAM .*/UsePAM yes/' /etc/ssh/sshd_config \
    && grep -qxF 'AllowTcpForwarding yes' /etc/ssh/sshd_config || echo 'AllowTcpForwarding yes' >> /etc/ssh/sshd_config \
    && grep -qxF 'X11Forwarding yes' /etc/ssh/sshd_config || echo 'X11Forwarding yes' >> /etc/ssh/sshd_config

# Install newer GraalVM version (Java 17) for native-image support.
RUN set -eux; \
    url="https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-${GRAALVM_VERSION}/graalvm-ce-java17-linux-${GRAALVM_ARCH}-${GRAALVM_VERSION}.tar.gz"; \
    curl -fsSL -o /tmp/graalvm.tgz "$url"; \
    tar -xzf /tmp/graalvm.tgz -C /root/tools; \
    /root/tools/graalvm-ce-java17-${GRAALVM_VERSION}/bin/gu install native-image; \
    ln -s /root/tools/graalvm-ce-java17-${GRAALVM_VERSION}/bin/native-image /usr/local/bin/native-image; \
    echo "GraalVM ${GRAALVM_VERSION} installed successfully."

# Remove old JAVA_HOME and GRAALVM_HOME from /etc/environment
RUN sed -i '/^GRAALVM_HOME=/d' /etc/environment \
    && sed -i '/^JAVA_HOME=/d' /etc/environment

# Set OpenJDK 17 as default Java version using update-alternatives
RUN update-alternatives --set java /usr/lib/jvm/java-17-openjdk-*/bin/java \
    && update-alternatives --set javac /usr/lib/jvm/java-17-openjdk-*/bin/javac

# Install newer Maven version (to support Java 17)
RUN set -eux; \
    url="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    curl -fsSL "$url" -o /tmp/maven.tar.gz; \
    tar -xzf /tmp/maven.tar.gz -C /opt; \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn; \
    echo "Maven ${MAVEN_VERSION} installed successfully.";

# Link Occlum-provided musl gcc toolings to /usr/local/bin
RUN for tool in /opt/occlum/toolchains/gcc/bin/*; do \
    ln -sf "$tool" "/usr/local/bin/$(basename "$tool")"; \
    done

# Install Oh My Zsh for the remote user (non-interactive)
RUN su - "${REMOTE_USER}" -c 'export RUNZSH=no CHSH=no; sh -c "$(curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)"'

# Copy .devcontainer/devcontainer-entrypoint.sh
COPY .devcontainer/devcontainer-entrypoint.sh /usr/local/bin/devcontainer-entrypoint
RUN chmod +x /usr/local/bin/devcontainer-entrypoint

EXPOSE 2222

ENTRYPOINT ["/usr/local/bin/devcontainer-entrypoint"]
CMD ["/usr/sbin/sshd", "-D", "-e"]
