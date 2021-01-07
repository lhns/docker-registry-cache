# docker-registry-cache
[![Docker Workflow](https://github.com/LolHens/docker-registry-cache/workflows/Docker/badge.svg)](https://github.com/LolHens/docker-registry-cache/actions?query=workflow%3ADocker)
[![Release Notes](https://img.shields.io/github/release/LolHens/docker-registry-cache.svg?maxAge=3600)](https://github.com/LolHens/docker-registry-cache/releases/latest)
[![Apache License 2.0](https://img.shields.io/github/license/LolHens/docker-registry-cache.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)

A multi-registry docker image cache.

It uses Docker's official [registry](https://docs.docker.com/registry/) internally and spawns one instance for each configured registry while it proxies requests to the corresponding registry.

## Usage
Instead of `my/image:latest` you use `172.0.0.1:5000/my/image:latest`.

This also works for `172.0.0.1:5000/ghcr.io/my/image:latest` and `172.0.0.1:5000/debian`.

## Stack Config
### MinIO Storage Backend
```yml
version: '3.7'

services:
  proxy:
    image: ghcr.io/lolhens/docker-registry-cache
    environment:
      CONFIG: |
        [
          "registry-1.docker.io",
          "ghcr.io",
          "gcr.io"
        ]
      REGISTRY_STORAGE: 's3'
      REGISTRY_STORAGE_REDIRECT_DISABLE: 'true'
      REGISTRY_STORAGE_S3_BUCKET: 'registry'
      REGISTRY_STORAGE_S3_REGION: 'minio'
      REGISTRY_STORAGE_S3_REGIONENDPOINT: 'http://s3:9000'
      REGISTRY_STORAGE_S3_ACCESSKEY: 'minioadmin'
      REGISTRY_STORAGE_S3_SECRETKEY: 'minioadmin'
    networks:
      - s3
    ports:
      - "5000:5000"
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

networks:
  s3:
    driver: overlay
```

### Filesystem Storage Backend
```yml
version: '3.7'

services:
  proxy:
    image: ghcr.io/lolhens/docker-registry-cache
    environment:
      CONFIG: |
        [
          "registry-1.docker.io",
          "ghcr.io",
          "gcr.io"
        ]
    ports:
      - "5000:5000"
```

### Corporate Proxy
```yml
    environment:
      http_proxy: 'https://my-proxy:8080'
      https_proxy: 'https://my-proxy:8080'
      no_proxy: '127.0.0.0/8,localhost,s3'
```

## Environment Variables
**CONFIG** must contain a JSON list of either a registry host strings or objects with the following structure `{"registry": "ghcr.io", "variables": {"TEST": "foo"}}`

The internally spawned registry processes will also inherit all environment variables so you can configure all internal registries as described in the [official documentation](https://docs.docker.com/registry/configuration/).
You can also configure the internal registries individually using the `variables` section in the aforementioned JSON structure.

## Docker builds
https://github.com/users/LolHens/packages/container/package/docker-registry-cache

## License
This project uses the Apache 2.0 License. See the file called LICENSE.
