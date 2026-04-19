# Requires: devcontainer CLI (npm i -g @devcontainers/cli) and Docker.
WORKSPACE       ?= ..
CONTAINER_NAME  ?= teaclave-sdk-devcontainer
SESSION         ?= dev

.PHONY: help devcontainer devcontainer-build devcontainer-up devcontainer-attach \
        devcontainer-attach-tmux devcontainer-down devcontainer-recreate

help:
	@echo "Usage: make [target]"
	@echo ""
	@echo "Targets:"
	@echo "  devcontainer             Build, up, and attach"
	@echo "  devcontainer-build       Build the devcontainer image"
	@echo "  devcontainer-up          Start the devcontainer"
	@echo "  devcontainer-attach      Attach to the devcontainer using bash"
	@echo "  devcontainer-attach-tmux Attach to the devcontainer using tmux"
	@echo "  devcontainer-down        Stop and remove the devcontainer"
	@echo "  devcontainer-recreate    Full cycle: down, build, up"

devcontainer: devcontainer-build devcontainer-up devcontainer-attach

devcontainer-build:
	devcontainer build --workspace-folder $(WORKSPACE)

devcontainer-up:
	devcontainer up --workspace-folder $(WORKSPACE)

devcontainer-attach: devcontainer-up
	devcontainer exec --workspace-folder $(WORKSPACE) zsh

devcontainer-attach-tmux: devcontainer-up
	devcontainer exec --workspace-folder $(WORKSPACE) tmux new-session -A -s $(SESSION)

devcontainer-down:
	docker stop $(CONTAINER_NAME) || true
	docker rm -f -v $(CONTAINER_NAME) || true

devcontainer-recreate: devcontainer-down devcontainer-build devcontainer-up
