# syntax=docker/dockerfile:1
# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The headless engine with its web interface: the native njmule executable on a distroless base
# (glibc, no shell, no package manager), run as an unprivileged user. From the repository root,
# for the platform of the machine that builds it:
#
#   docker build -f packaging/docker/Dockerfile -t njmule .
#
# The release workflow compiles nothing here: it hands over the executables of its Linux jobs as
# the build context "binary", a directory with amd64/njmule and arm64/njmule, and builds both
# platforms of the image from them.

# native-image does not cross-compile: this stage runs on the platform of the image.
FROM ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1@sha256:287f82eefd5d631865302df00a8555bcf80e3c0291da171fb7f72d594a542315 AS build
ARG TARGETARCH
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,id=njmule-gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain :njmule-app:nativeCompile \
    && mkdir -p "/out/${TARGETARCH}" \
    && cp njmule-app/build/native/nativeCompile/njmule "/out/${TARGETARCH}/njmule"

FROM scratch AS binary
COPY --from=build /out/ /

# glibc and zlib (the executable links both), time zones and a passwd file; no shell, no package
# manager.
FROM gcr.io/distroless/base-debian13:latest@sha256:389cad21f73e4c37b94ffe5b13736d5a92bd5bd3c6c6b38c2be1c881e14ba2bd AS base

# The user, its directories and the notices, prepared on the build platform: no emulation.
FROM --platform=${BUILDPLATFORM} busybox:1.37.0@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e AS layout
COPY --from=base /etc/passwd /etc/group /out/etc/
COPY LICENSE NOTICE /out/doc/
RUN echo 'njmule:x:1000:1000:njMule:/njmule:/sbin/nologin' >> /out/etc/passwd \
    && echo 'njmule:x:1000:' >> /out/etc/group \
    && chmod 644 /out/doc/* \
    && mkdir -p /out/home/njmule/config /out/home/njmule/data /out/home/njmule/temp \
        /out/home/njmule/incoming

FROM base
ARG TARGETARCH
LABEL org.opencontainers.image.title="njMule" \
    org.opencontainers.image.description="eMule-compatible eD2K and Kad client: headless engine with web interface" \
    org.opencontainers.image.licenses="GPL-3.0-or-later" \
    org.opencontainers.image.source="https://github.com/njMule/njMule" \
    org.opencontainers.image.url="https://github.com/njMule/njMule"
COPY --from=layout /out/etc/passwd /out/etc/group /etc/
COPY --from=layout --chown=1000:1000 /out/home/ /
COPY --from=layout /out/doc/ /usr/share/doc/njmule/
COPY --from=binary --chmod=755 /${TARGETARCH}/njmule /usr/local/bin/njmule
USER 1000:1000
WORKDIR /njmule
ENV HOME=/njmule
# eD2K TCP, client UDP (eD2K and Kad), web interface.
EXPOSE 4662/tcp 4672/udp 4711/tcp
# One volume for everything; temp and incoming may be mounted on their own below it.
VOLUME ["/njmule"]
ENTRYPOINT ["/usr/local/bin/njmule", "--config-dir", "/njmule"]
CMD ["--webui-password-file", "/run/secrets/njmule_webui_password", "--webui-lan"]
