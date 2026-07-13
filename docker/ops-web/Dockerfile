FROM node:22.14.0-bookworm-slim

WORKDIR /workspace/ops-web

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY docker/ops-web/docker-entrypoint.sh /usr/local/bin/ops-web-entrypoint.sh
RUN chmod +x /usr/local/bin/ops-web-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/ops-web-entrypoint.sh"]
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "5173"]
