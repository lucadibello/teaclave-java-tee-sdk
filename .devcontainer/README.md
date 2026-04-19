# Teaclave Java TEE SDK Devcontainer

This directory contains the configuration for a standardized development environment for the Teaclave Java TEE SDK. It provides all necessary toolchains, including GraalVM 17, Maven 3.9, and the Occlum-provided musl-gcc toolset.

## Prerequisites

To use this devcontainer, ensure the following are installed on your host machine:

1.  **Docker**: Required to run the container.
2.  **Node.js & NPM**: Required for the devcontainer CLI.
3.  **Devcontainer CLI**: Install globally via npm:
    ```bash
    npm install -g @devcontainers/cli
    ```
4.  **SGX Hardware & Drivers**: The container requires access to `/dev/sgx_enclave` and related devices.

## Quick Start

A `Makefile` is provided to simplify lifecycle management. Run these commands from the `.devcontainer` directory:

| Action | Command | Description |
| :--- | :--- | :--- |
| **Full Setup** | `make devcontainer` | Builds, starts, and attaches to the container in one go. |
| **Build** | `make devcontainer-build` | Builds the Docker image. |
| **Start** | `make devcontainer-up` | Starts the container in the background. |
| **Attach** | `make devcontainer-attach` | Opens a Zsh shell inside the running container. |
| **Attach (Tmux)**| `make devcontainer-attach-tmux` | Attaches to a persistent tmux session inside the container. |
| **Destroy** | `make devcontainer-down` | Stops and removes the container and its volumes. |
| **Recreate** | `make devcontainer-recreate` | Destroys the existing container and builds/starts a fresh one. |

## Configuration Details

- **Base Image**: `teaclave/teaclave-java-tee-sdk:v0.1.0-ubuntu18.04`
- **SSH Port**: `2222` (accessible via `localhost:2222` from the host).
- **Default User**: `dev` (passwordless sudo enabled).
- **Shell**: `zsh` with Oh My Zsh.
- **Tools**:
  - **GraalVM**: 22.2.0 (Java 17) with `native-image`.
  - **Maven**: 3.9.12.
  - **Occlum Toolchain**: Symlinked to `/usr/local/bin` (e.g., `occlum-gcc`, `x86_64-linux-musl-gcc`).
- **SGX Access**: The container is started with `--privileged` and device mounts for SGX support.

## Environment Variables

The `devcontainer.json` is configured to use your host's SSH agent if `SSH_AUTH_SOCK` is set. You can also customize the Git identity by editing the `args` section in `devcontainer.json`.
