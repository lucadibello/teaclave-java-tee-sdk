#!/usr/bin/env bash

set -eux;

# Install GraalVM processor as Maven artifact
mvn install:install-file \
    -DgroupId=org.graalvm.compiler \
    -DartifactId=graal-processor \
    -Dversion=22.2.0 \
    -Dpackaging=jar \
    -Dfile="${GRAALVM_HOME}/lib/graal/graal-processor.jar"

# Build TeaClave SDK (requires GraalVM as JAVA_HOME for org.graalvm.compiler.* packages)
export JAVA_HOME="${GRAALVM_HOME}"; \
mkdir -p /opt/javaenclave; \
mvn -f sdk/pom.xml clean install -DskipTests; \
cp -r sdk/native/bin /opt/javaenclave; \
cp -r sdk/native/config /opt/javaenclave; \
cp -r sdk/native/script/build_app /opt/javaenclave;

echo "TeaClave SDK build complete. Binaries and scripts are located in /opt/javaenclave/bin, /opt/javaenclave/config, and /opt/javaenclave/build_app."