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

# Setting PCCS_URL for SGX Remote Attestation.
# PCCS_URL should be changed according to ECS location.
# PCCS_URL=https://sgx-dcap-server.cn-hongkong.aliyuncs.com/sgx/certification/v3/
echo "PCCS_URL=https://sgx-dcap-server.cn-hongkong.aliyuncs.com/sgx/certification/v3/" > /etc/sgx_default_qcnl.conf
echo "USE_SECURE_CERT=TRUE" >> /etc/sgx_default_qcnl.conf

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Compile test project (we skip native-tests on package since we are using surefire)
mvn -f "${SCRIPT_DIR}/pom.xml" clean package -Pnative -DskipNativeTests=true

# Run host tests via surefire (JUnit 6)
OCCLUM_RELEASE_ENCLAVE=true mvn -f "${SCRIPT_DIR}/pom.xml" test -pl host -am
