FROM golang:1.26.5-alpine AS caddy-build

ARG CADDY_VERSION=v2.11.4
ARG XCADDY_VERSION=v0.4.6
RUN go install github.com/caddyserver/xcaddy/cmd/xcaddy@${XCADDY_VERSION} && \
    CGO_ENABLED=0 xcaddy build ${CADDY_VERSION} --output /go/bin/caddy

FROM caddy:2.11.4-alpine

USER root
RUN apk upgrade --no-cache
COPY --from=caddy-build /go/bin/caddy /usr/bin/caddy
RUN caddy version
