FROM registry:3

RUN apk --no-cache add openjdk17 --repository=http://dl-cdn.alpinelinux.org/alpine/edge/community
COPY target/scala-*/*.sh.bat /docker-registry-cache.sh.bat

ENTRYPOINT ["/bin/sh", "-c", "exec /docker-registry-cache.sh.bat"]
