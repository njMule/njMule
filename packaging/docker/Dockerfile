# syntax=docker/dockerfile:1
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The headless engine with its web interface, run as an unprivileged user: on amd64 the Rust
# njmule executable built fully static with musl, alone on an empty base (no libc, no shell, no
# package manager, plus CA certificates for TLS); on arm64 the glibc executable on a distroless
# base. From the repository root, for the platform of the machine that builds it:
#
#   docker build -f packaging/docker/Dockerfile -t njmule .
#
# The release workflow compiles nothing here: it hands over the executables of its Linux jobs as
# the build context "binary", a directory with amd64/njmule (static) and arm64/njmule, and builds
# both platforms of the image from them.

FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS ui
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,id=njmule-gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain :njmule-ui:compress

FROM rust:1.99.1@sha256:8a4e90c7946b74626488b1446f712c0fe0ed1a596cbd3d871684bc9c5433ae07 AS build
ARG TARGETARCH
WORKDIR /src
COPY . .
COPY --from=ui /src/njmule-ui/build/shipped /src/njmule-ui/build/shipped
RUN --mount=type=cache,target=/usr/local/cargo/registry,id=njmule-cargo-registry,sharing=locked \
    --mount=type=cache,target=/src/njmule-engine/target,id=njmule-cargo-target,sharing=locked \
    set -eu; \
    mkdir -p "/out/${TARGETARCH}"; \
    if [ "$TARGETARCH" = amd64 ]; then \
        apt-get update -qq && apt-get install -y -qq --no-install-recommends musl-tools; \
        rustup target add x86_64-unknown-linux-musl; \
        CC_x86_64_unknown_linux_musl=musl-gcc cargo build --manifest-path njmule-engine/Cargo.toml \
            --locked --release --target x86_64-unknown-linux-musl --bin njmule; \
        cp njmule-engine/target/x86_64-unknown-linux-musl/release/njmule "/out/${TARGETARCH}/njmule"; \
    else \
        cargo build --manifest-path njmule-engine/Cargo.toml --locked --release --bin njmule; \
        cp njmule-engine/target/release/njmule "/out/${TARGETARCH}/njmule"; \
    fi

FROM scratch AS binary
COPY --from=build /out/ /

# glibc, CA certificates, time zones and a passwd file; no shell, no package manager.
FROM gcr.io/distroless/base-debian13:latest@sha256:f5e95195e998742ff590b7569bae479accce1226e51ae6c3118954030121cdcb AS distroless

# The static musl executable needs only the CA certificate bundle (taken from distroless) and the
# resolver files Docker mounts.
FROM scratch AS base-amd64
COPY --from=distroless /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt

FROM distroless AS base-arm64

# The user, its directories, /tmp and the notices (with musl's notice, which the static executable
# holds), prepared on the build platform: no emulation.
FROM --platform=${BUILDPLATFORM} busybox:1.38.0@sha256:a46257e06c82ad85242d0d38077d0a52d9896389ca60a1a59d3463db586e5fa4 AS layout
COPY --from=distroless /etc/passwd /etc/group /out/etc/
COPY LICENSE NOTICE THIRD-PARTY.tx[t] packaging/synology/licenses/musl-COPYRIGHT packaging/synology/licenses/zlib-LICENSE /out/doc/
RUN echo 'njmule:x:1000:1000:njMule:/njmule:/sbin/nologin' >> /out/etc/passwd \
    && echo 'njmule:x:1000:' >> /out/etc/group \
    && chmod 644 /out/doc/* \
    && mkdir -p /out/home/njmule/config /out/home/njmule/data /out/home/njmule/temp \
        /out/home/njmule/incoming /out/tmp

FROM base-${TARGETARCH}
ARG TARGETARCH
LABEL org.opencontainers.image.title="njMule" \
    org.opencontainers.image.description="eMule-compatible eD2K and Kad client: headless engine with web interface" \
    org.opencontainers.image.licenses="GPL-3.0-or-later" \
    org.opencontainers.image.source="https://github.com/njMule/njMule" \
    org.opencontainers.image.url="https://github.com/njMule/njMule"
COPY --from=layout /out/etc/passwd /out/etc/group /etc/
COPY --from=layout --chmod=1777 /out/tmp /tmp
COPY --from=layout --chown=1000:1000 /out/home/ /
COPY --from=layout /out/doc/ /usr/share/doc/njmule/
COPY --from=binary --chmod=755 /${TARGETARCH}/njmule /usr/local/bin/njmule
USER 1000:1000
WORKDIR /njmule
ENV HOME=/njmule
# The update check tells that a new image is pulled, never that njMule replaces itself.
ENV NJMULE_INSTALL_KIND=docker
# eD2K TCP, client UDP (eD2K and Kad), web interface.
EXPOSE 4662/tcp 4672/udp 4711/tcp
# One volume for everything; temp and incoming may be mounted on their own below it.
VOLUME ["/njmule"]
ENTRYPOINT ["/usr/local/bin/njmule", "--config-dir", "/njmule"]
CMD ["--webui-password-file", "/run/secrets/njmule_webui_password", "--webui-lan"]
