# syntax=docker/dockerfile:1
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The headless engine with its web interface, run as an unprivileged user: on amd64 the native
# njmule executable built fully static with musl, alone on an empty base (no libc, no shell, no
# package manager); on arm64, where GraalVM builds no static executable, the glibc one on a
# distroless base. From the repository root, for the platform of the machine that builds it:
#
#   docker build -f packaging/docker/Dockerfile -t njmule .
#
# The release workflow compiles nothing here: it hands over the executables of its Linux jobs as
# the build context "binary", a directory with amd64/njmule (static) and arm64/njmule, and builds
# both platforms of the image from them.

# native-image does not cross-compile: this stage runs on the platform of the image. On amd64 it
# takes the musl toolchain GraalVM supports, checked against its SHA-256.
FROM ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1@sha256:287f82eefd5d631865302df00a8555bcf80e3c0291da171fb7f72d594a542315 AS build
ARG TARGETARCH
ARG MUSL=musl-toolchain-1.2.5-oracle-00001-linux-amd64.tar.gz
ARG MUSL_SHA256=77a60e1d31303f214e1c9d8e5843abcce2743a0c6f6b436e251f69a3161801ad
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,id=njmule-gradle,sharing=locked \
    --mount=type=cache,target=/musl,id=njmule-musl,sharing=locked \
    set -eu; \
    static=""; \
    if [ "$TARGETARCH" = amd64 ]; then \
        if ! echo "$MUSL_SHA256  /musl/$MUSL" | sha256sum --check --status; then \
            curl -fsSL -o "/musl/$MUSL" "https://gds.oracle.com/download/bfs/archive/$MUSL"; \
            echo "$MUSL_SHA256  /musl/$MUSL" | sha256sum --check; \
        fi; \
        mkdir -p /opt/musl && tar -C /opt/musl -xzf "/musl/$MUSL"; \
        export PATH="/opt/musl/musl-toolchain/bin:$PATH"; \
        static="-Pnative.static=musl"; \
    fi; \
    ./gradlew --no-daemon --console=plain :njmule-app:nativeCompile $static; \
    mkdir -p "/out/${TARGETARCH}"; \
    cp njmule-app/build/native/nativeCompile/njmule "/out/${TARGETARCH}/njmule"

FROM scratch AS binary
COPY --from=build /out/ /

# glibc and zlib (the executable links both), time zones and a passwd file; no shell, no package
# manager.
FROM gcr.io/distroless/base-debian13:latest@sha256:389cad21f73e4c37b94ffe5b13736d5a92bd5bd3c6c6b38c2be1c881e14ba2bd AS distroless

# The static executable brings what it needs: certificates and time zones are in the image of Java
# it was built from, and musl reads only the resolver files Docker mounts.
FROM scratch AS base-amd64
FROM distroless AS base-arm64

# The user, its directories, /tmp and the notices (with those of musl and zlib, which the static
# executable holds), prepared on the build platform: no emulation.
FROM --platform=${BUILDPLATFORM} busybox:1.38.0@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e AS layout
COPY --from=distroless /etc/passwd /etc/group /out/etc/
COPY LICENSE NOTICE packaging/synology/licenses/musl-COPYRIGHT packaging/synology/licenses/zlib-LICENSE /out/doc/
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
