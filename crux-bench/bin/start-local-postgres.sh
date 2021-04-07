#!/usr/bin/env bash
set -x
set -e

docker pull postgres:13.2
docker run -e POSTGRES_PASSWORD=password -it -p 5432:5432 postgres:13.2
