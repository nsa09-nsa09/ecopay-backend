#!/usr/bin/env bash
set -e

if [ ! -f .env ]; then
  echo ".env file not found"
  exit 1
fi

set -a
source .env
set +a

if [ -z "${FLYWAY_ENABLED:-}" ]; then
  export FLYWAY_ENABLED=false
fi

if [ -z "${JPA_DDL_AUTO:-}" ]; then
  export JPA_DDL_AUTO=update
fi

./mvnw spring-boot:run
