#!/bin/bash

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

set -e

# shellcheck disable=SC2006
export BUILD_SCRIPT_DIR=`dirname "$0"`

# Validate required arguments
if [ -z "$1" ]; then
    echo "Error: ENCLAVE_BASE_DIR is required."
    echo "Usage: $0 <ENCLAVE_BASE_DIR> <ENCLAVE_PLATFORM> [ENCLAVE_PRIVATE_PEM_PATH]"
    echo ""
    echo "Arguments:"
    echo "  ENCLAVE_BASE_DIR         Path to the enclave project's base directory"
    echo "  ENCLAVE_PLATFORM         Platform type: TEE_SDK or EMBEDDED_LIB_OS (can be colon-separated for multiple)"
    echo "  ENCLAVE_PRIVATE_PEM_PATH (Optional) Path to private PEM file for signing"
    echo ""
    echo "Example:"
    echo "  $0 /path/to/enclave TEE_SDK"
    echo "  $0 /path/to/enclave TEE_SDK:EMBEDDED_LIB_OS /path/to/private.pem"
    exit 1
fi

if [ -z "$2" ]; then
    echo "Error: ENCLAVE_PLATFORM is required."
    echo "Usage: $0 <ENCLAVE_BASE_DIR> <ENCLAVE_PLATFORM> [ENCLAVE_PRIVATE_PEM_PATH]"
    echo ""
    echo "Supported platforms: TEE_SDK, EMBEDDED_LIB_OS"
    exit 1
fi

# set enclave project's base dir path (convert to absolute path).
export ENCLAVE_BASE_DIR="$(cd "$1" && pwd)"
# set enclave platform, such as mock_in_svm and tee_sdk.
enclave_platform_config=$2
# get enclave private pem for making .signed file.
export ENCLAVE_PRIVATE_PEM_PATH=$3

# Create a native image building workspace in application's enclave submodule.
mkdir -p "${ENCLAVE_BASE_DIR}"/target/enclave_workspace
# copy Makefile script to enclave_workspace.
cp -r "${BUILD_SCRIPT_DIR}"/Makefile "${ENCLAVE_BASE_DIR}"/target/enclave_workspace

# cd to enclave workspace.
cd "${ENCLAVE_BASE_DIR}"/target/enclave_workspace

# process supported enclave platform set
OLD_IFS="$IFS"
IFS=":"
enclave_platform_array=($enclave_platform_config)
IFS="$OLD_IFS"

# Setting TEE Platform for makefile build process.
# shellcheck disable=SC2068
for enclave_platform in ${enclave_platform_array[@]}
do
  echo "$enclave_platform"
  # set "enclave_platform" as TRUE to indicate how
  # to compile jni.so and edge routine
  export "$enclave_platform"=TRUE
done

make -f ./Makefile all
