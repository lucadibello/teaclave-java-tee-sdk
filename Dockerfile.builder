# syntax=docker/dockerfile:1.4
# Minimal builder image for TeaClave Java TEE SDK
# Use this image to build projects without the full devcontainer overhead
FROM ubuntu:24.04

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ARG GRAALVM_VERSION=22.2.0
ARG GRAALVM_ARCH=amd64
ARG MAVEN_VERSION=3.9.12

ENV DEBIAN_FRONTEND=noninteractive \
    GRAALVM_HOME=/opt/graalvm \
    JAVA_HOME=/opt/graalvm \
    SGX_SDK=/opt/teesdk/sgxsdk \
    MAVEN_OPTS="-Xmx4g -Xms512m"

ENV PATH="${SGX_SDK}/bin:${SGX_SDK}/bin/x64:${JAVA_HOME}/bin:/opt/maven/bin:${PATH}" \
    PKG_CONFIG_PATH="${SGX_SDK}/pkgconfig" \
    LD_LIBRARY_PATH="${SGX_SDK}/sdk_libs"

# Install build dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        # Build toolchain
        autoconf automake build-essential cmake g++ gcc make zlib1g-dev \
        # musl toolchain for GraalVM native-image
        musl musl-dev musl-tools \
        # Required utilities
        ca-certificates curl git tar unzip xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && ln -sf /usr/bin/musl-gcc /usr/local/bin/x86_64-linux-musl-gcc

# Build zlib for musl (required for GraalVM native-image with --libc=musl)
RUN set -eux; \
    curl -fsSL https://zlib.net/zlib-1.3.1.tar.gz -o /tmp/zlib.tar.gz; \
    tar -xzf /tmp/zlib.tar.gz -C /tmp; \
    cd /tmp/zlib-1.3.1; \
    CC=musl-gcc ./configure --static --prefix=/usr/local/musl; \
    make -j$(nproc); \
    make install; \
    ln -sf /usr/local/musl/lib/libz.a /usr/lib/x86_64-linux-musl/libz.a; \
    ln -sf /usr/local/musl/lib/libz.a /usr/lib/libz.a; \
    rm -rf /tmp/zlib.tar.gz /tmp/zlib-1.3.1

# Install Intel SGX SDK and runtime libraries
RUN set -eux; \
    install -d -m 0755 /etc/apt/keyrings; \
    curl -fsSL "https://download.01.org/intel-sgx/sgx_repo/ubuntu/intel-sgx-deb.key" -o /etc/apt/keyrings/intel-sgx-keyring.asc; \
    echo 'deb [signed-by=/etc/apt/keyrings/intel-sgx-keyring.asc arch=amd64] https://download.01.org/intel-sgx/sgx_repo/ubuntu noble main' > /etc/apt/sources.list.d/intel-sgx.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        libsgx-launch-dev \
        libsgx-urts \
        libsgx-uae-service \
        libsgx-enclave-common \
        libsgx-enclave-common-dev \
        libsgx-dcap-ql \
        libsgx-dcap-ql-dev \
        libsgx-dcap-quote-verify-dev \
        libsgx-dcap-default-qpl; \
    rm -rf /var/lib/apt/lists/*

# Install Intel SGX SDK
RUN set -eux; \
    curl -fsSL -o /tmp/sgx_sdk.bin \
        "https://download.01.org/intel-sgx/sgx-linux/2.26/distro/ubuntu24.04-server/sgx_linux_x64_sdk_2.26.100.0.bin"; \
    chmod +x /tmp/sgx_sdk.bin; \
    mkdir -p /opt/teesdk; \
    /tmp/sgx_sdk.bin --prefix /opt/teesdk; \
    rm -f /tmp/sgx_sdk.bin; \
    printf '%s\n' /opt/teesdk/sgxsdk/sdk_libs > /etc/ld.so.conf.d/sgxsdk.conf; \
    ldconfig

# Install GraalVM
RUN set -eux; \
    curl -fsSL -o /tmp/graalvm.tgz \
        "https://github.com/graalvm/graalvm-ce-builds/releases/download/vm-${GRAALVM_VERSION}/graalvm-ce-java17-linux-${GRAALVM_ARCH}-${GRAALVM_VERSION}.tar.gz"; \
    mkdir -p /opt; \
    tar -xzf /tmp/graalvm.tgz -C /opt; \
    mv /opt/graalvm-ce-java17-${GRAALVM_VERSION} /opt/graalvm; \
    rm -f /tmp/graalvm.tgz; \
    /opt/graalvm/bin/gu install native-image

# Install Maven
RUN set -eux; \
    curl -fsSL -o /tmp/maven.tar.gz \
        "https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    tar -xzf /tmp/maven.tar.gz -C /opt; \
    mv /opt/apache-maven-${MAVEN_VERSION} /opt/maven; \
    rm -f /tmp/maven.tar.gz

# Copy and build TeaClave Java TEE SDK
COPY sdk /tmp/teaclave-java-tee-sdk/sdk

# Install GraalVM processor and build SDK
RUN set -eux; \
    mvn install:install-file \
        -DgroupId=org.graalvm.compiler \
        -DartifactId=graal-processor \
        -Dversion=22.2.0 \
        -Dpackaging=jar \
        -Dfile="${GRAALVM_HOME}/lib/graal/graal-processor.jar"; \
    mvn -f /tmp/teaclave-java-tee-sdk/sdk/pom.xml clean install -DskipTests; \
    mkdir -p /opt/javaenclave; \
    cp -r /tmp/teaclave-java-tee-sdk/sdk/native/bin /opt/javaenclave; \
    cp -r /tmp/teaclave-java-tee-sdk/sdk/native/config /opt/javaenclave; \
    cp -r /tmp/teaclave-java-tee-sdk/sdk/native/script/build_app /opt/javaenclave; \
    rm -rf /tmp/teaclave-java-tee-sdk

WORKDIR /workspace

# Default command: show usage
CMD ["echo", "TeaClave Java TEE SDK Builder. Mount your project to /workspace and run: mvn -Pnative clean package"]
