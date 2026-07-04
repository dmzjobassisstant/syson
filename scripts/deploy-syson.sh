#!/bin/bash
# SysON deploy script — rebuilds Docker image and restarts container
# Run from /root/syson-fork
set -e

echo "=== Building Docker image ==="
docker build -t syson-rbac:latest backend/application/syson-application

echo "=== Stopping and removing old container ==="
docker stop syson 2>/dev/null || true
docker rm syson 2>/dev/null || true

echo "=== Starting new container ==="
docker run -d --name syson --restart unless-stopped --network host \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/syson \
    -e SPRING_DATASOURCE_USERNAME=syson \
    -e SPRING_DATASOURCE_PASSWORD=syson \
    -e SPRING_FLYWAY_ENABLED=false \
    -e SIRIUS_COMPONENTS_CORS_ALLOWEDORIGINPATTERNS="*" \
    -e SERVER_PORT=8080 \
    -e SYSON_AUTH_JWT_SECRET=changeme-please-override-in-production \
    syson-rbac:latest

echo "=== Waiting for startup ==="
for i in $(seq 1 60); do
    if curl -sf http://localhost:8080/ > /dev/null 2>&1; then
        echo "READY (attempt $i)"
        exit 0
    fi
    sleep 2
done
echo "FAILED to start"
docker logs --tail 50 syson
exit 1
