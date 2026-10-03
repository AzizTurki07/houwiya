FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# The base image ships an apt source for Yarn whose signing key no longer verifies
# (NO_PUBKEY 62D54FD4003F6525), so the first `apt-get update` -- in the docker-in-docker
# feature -- fails and the whole container build with it. The project uses npm, not Yarn.
RUN rm -f /etc/apt/sources.list.d/yarn*.list

# kind: Kubernetes in Docker, for running the app as pods (k8s/up.sh). Pinned by checksum.
ARG KIND_VERSION=v0.33.0
ARG KIND_SHA256=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d
RUN curl -fsSLo /usr/local/bin/kind "https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/kind-linux-amd64" \
    && echo "${KIND_SHA256}  /usr/local/bin/kind" | sha256sum -c - \
    && chmod +x /usr/local/bin/kind
