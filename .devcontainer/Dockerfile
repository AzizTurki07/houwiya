FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# The base image ships an apt source for Yarn whose signing key no longer verifies
# (NO_PUBKEY 62D54FD4003F6525), so the first `apt-get update` -- in the docker-in-docker
# feature -- fails and the whole container build with it. The project uses npm, not Yarn.
RUN rm -f /etc/apt/sources.list.d/yarn*.list
