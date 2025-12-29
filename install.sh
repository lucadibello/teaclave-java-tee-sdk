#!/bin/bash

TEMP_DIR=$(mktemp -d)
DEST_DIR=/opt/javaenclave
GRAALVM_VERSION=17.0.9 # GraalVM 17.0.9 uses new naming: graalvm-community-jdk-<version>
GRAALVM_HOME=${GRAALVM_HOME:-"/root/tools/graalvm-community-openjdk-${GRAALVM_VERSION}+9.1"}

# ensure graalvm is installed
if [ -z "$GRAALVM_HOME" ]; then
    echo "GRAALVM_HOME is not set. Please install GraalVM and set GRAALVM_HOME environment variable."
    exit 1
fi

if [ ! -d "$GRAALVM_HOME" ]; then
    echo "GRAALVM_HOME directory does not exist: $GRAALVM_HOME"
    exit 1
fi

if [ ! -f "$GRAALVM_HOME/bin/java" ]; then
    echo "GraalVM java binary not found in GRAALVM_HOME: $GRAALVM_HOME/bin/java"
    exit 1
fi

set -e

# Install GraalVM graal-processor as Maven artifact
/usr/local/bin/mvn install:install-file \
    -DgroupId=org.graalvm.compiler \
    -DartifactId=graal-processor \
    -Dversion=${GRAALVM_VERSION} \
    -Dpackaging=jar \
    -Dfile="${GRAALVM_HOME}/lib/graal/graal-processor.jar"

# Create destination directory
mkdir -p $DEST_DIR

# Pre-download all dependencies and plugins
/usr/local/bin/mvn -f sdk/pom.xml \
    dependency:resolve \
    dependency:resolve-plugins

# Build and install teaclave sdk
mkdir -p ${DEST_DIR}
mvn -f sdk/pom.xml clean install -Pnative
cp -r sdk/native/bin ${DEST_DIR}
cp -r sdk/native/config ${DEST_DIR}
cp -r sdk/native/script/build_app ${DEST_DIR}
echo "Teaclave Java TEE SDK has been successfully installed to ${DEST_DIR}"
