FROM lolhens/sbt-graal:21.2.0-java11 as builder

COPY . .
ARG CI_VERSION=""
RUN sbt assembly
RUN cp target/scala-*/docker-registry-cache*.sh.bat docker-registry-cache.sh.bat

FROM registry:2

RUN apk --no-cache add openjdk11 --repository=http://dl-cdn.alpinelinux.org/alpine/edge/community
COPY --from=builder /root/docker-registry-cache.sh.bat /.

ENTRYPOINT ["/bin/sh", "-c", "exec /docker-registry-cache.sh.bat"]
