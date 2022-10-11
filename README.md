# docker-registry-cache

[![Docker Workflow](https://github.com/lhns/docker-registry-cache/workflows/build/badge.svg)](https://github.com/lhns/docker-registry-cache/actions?query=workflow%3Abuild)
[![Release Notes](https://img.shields.io/github/release/lhns/docker-registry-cache.svg?maxAge=3600)](https://github.com/lhns/docker-registry-cache/releases/latest)
[![Apache License 2.0](https://img.shields.io/github/license/lhns/docker-registry-cache.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

A multi-registry docker image cache.

It uses Docker's official [registry](https://docs.docker.com/registry/) internally and spawns one instance for each
configured registry while it proxies requests to the corresponding registry.

## Usage

Instead of `my/image:latest` you just specify `127.0.0.1:5000/my/image:latest`.

This also works for `127.0.0.1:5000/ghcr.io/my/image:latest` and `127.0.0.1:5000/debian`.

## Installation
- Deploy the stack config as shown below
- Add the following to your `/etc/docker/daemon.json`:
```yml
{
  "insecure-registries" : ["127.0.0.1:5000"]
}
```

- Restart the docker daemon (or [reload](https://gist.github.com/lhns/72fb1dfba4d0dada78ba7d9b69ed76de))
- Change all image references that you want to cache from `my/image:latest` to `127.0.0.1:5000/my/image:latest`

## Stack Config
### Filesystem Storage Backend
```yml
version: '3.7'

services:
  proxy:
    image: ghcr.io/lhns/docker-registry-cache:0.1.4
    environment:
      CONFIG: |
        [
          {"registry": "registry-1.docker.io", "variables": {"REGISTRY_PROXY_USERNAME": "my_dockerhub_id", "REGISTRY_PROXY_PASSWORD": "my_dockerhub_token"}},
          "ghcr.io",
          "gcr.io"
        ]
      REGISTRY_STORAGE_DELETE_ENABLED: 'true'
    volumes:
      - /docker-registry-cache:/var/lib/registry
    ports:
      - "5000:5000"
  zzz_proxy:
    # This dummy service prevents the image from getting pruned
    image: ghcr.io/lhns/docker-registry-cache:0.1.4
    entrypoint: tail -f /dev/null
    deploy:
      mode: global
```

### MinIO Storage Backend
```yml
version: '3.7'

services:
  proxy:
    image: ghcr.io/lhns/docker-registry-cache:0.1.4
    environment:
      CONFIG: |
        [
          {"registry": "registry-1.docker.io", "variables": {"REGISTRY_PROXY_USERNAME": "my_dockerhub_id", "REGISTRY_PROXY_PASSWORD": "my_dockerhub_token"}},
          "ghcr.io",
          "gcr.io"
        ]
      REGISTRY_STORAGE: 's3'
      REGISTRY_STORAGE_S3_BUCKET: 'registry'
      REGISTRY_STORAGE_S3_REGION: 'minio'
      REGISTRY_STORAGE_S3_REGIONENDPOINT: 'http://s3:9000'
      REGISTRY_STORAGE_S3_ACCESSKEY: 'minioadmin'
      REGISTRY_STORAGE_S3_SECRETKEY: 'minioadmin'
      REGISTRY_STORAGE_DELETE_ENABLED: 'true'
    networks:
      - s3
    ports:
      - "5000:5000"
    deploy:
      update_config:
        order: start-first
  s3:
    image: minio/minio
    entrypoint: /bin/sh -c 'mkdir /data/registry & exec /usr/bin/docker-entrypoint.sh "$$@"' --
    command: server --address 0.0.0.0:9000 /data
    environment:
      MINIO_ACCESS_KEY: 'minioadmin'
      MINIO_SECRET_KEY: 'minioadmin'
    volumes:
      - /docker-registry-cache:/data
    networks:
      - s3
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 15s
  zzz_proxy:
    # This dummy service prevents the image from getting pruned
    image: ghcr.io/lhns/docker-registry-cache:0.1.4
    entrypoint: tail -f /dev/null
    deploy:
      mode: global
  zzz_s3:
    # This dummy service prevents the image from getting pruned
    image: minio/minio
    entrypoint: tail -f /dev/null
    deploy:
      mode: global

networks:
  s3:
    driver: overlay
```

### Corporate Proxy
```yml
    environment:
      http_proxy: 'https://my-proxy:8080'
      https_proxy: 'https://my-proxy:8080'
      no_proxy: '127.0.0.0/8,localhost,s3'
```

## Environment Variables
**CONFIG** must contain a JSON list of either registry host strings or objects with the following structure `{"registry": "ghcr.io", "variables": {"TEST": "foo"}}`

The internally spawned registry processes will also inherit all environment variables so you can configure all internal registries as described in the [official documentation](https://docs.docker.com/registry/configuration/).
You can also configure the internal registries individually using the `variables` section in the aforementioned JSON structure.

## Docker builds
https://github.com/users/lhns/packages/container/package/docker-registry-cache

https://hub.docker.com/r/lolhens/docker-registry-cache

## License
This project uses the Apache 2.0 License. See the file called LICENSE.
