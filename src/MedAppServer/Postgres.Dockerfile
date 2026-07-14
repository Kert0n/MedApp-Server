FROM golang:1.26.5-alpine AS gosu-build

# gosu 1.19 source, rebuilt with a Go toolchain containing current stdlib fixes.
ARG GOSU_COMMIT=6456aaa0f3c854d199d0f037f068eb97515b7513
RUN CGO_ENABLED=0 go install github.com/tianon/gosu@${GOSU_COMMIT}

FROM postgres:18.4-alpine3.23

RUN apk upgrade --no-cache
COPY --from=gosu-build /go/bin/gosu /usr/local/bin/gosu
RUN gosu --version
