FROM ghcr.io/astral-sh/uv:0.7.13@sha256:6c1e19020ec221986a210027040044a5df8de762eb36d5240e382bc41d7a9043 AS uv

FROM node:22.14.0-bookworm-slim AS ops-web-build
WORKDIR /workspace/ops-web
COPY ops-web/package.json ops-web/package-lock.json ./
RUN npm ci
COPY ops-web/ ./
RUN npm run build

FROM python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7 AS base

ARG APP_UID=10001
ARG APP_GID=10001
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_LINK_MODE=copy

RUN groupadd --gid "${APP_GID}" loanagent \
    && useradd --uid "${APP_UID}" --gid "${APP_GID}" --create-home loanagent \
    && mkdir -p /home/loanagent/.cache/uv /home/loanagent/.venv \
    && chown -R loanagent:loanagent /home/loanagent

COPY --from=uv /uv /uvx /bin/

FROM base AS dev

ENV UV_PROJECT_ENVIRONMENT=/home/loanagent/.venv
WORKDIR /workspace/control-plane
USER loanagent:loanagent

FROM base AS runtime-builder

ENV UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /app
COPY control-plane/pyproject.toml control-plane/uv.lock control-plane/README.md ./
COPY control-plane/src ./src
RUN uv sync --frozen --no-dev --no-editable

FROM python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7 AS runtime

ARG APP_UID=10001
ARG APP_GID=10001
ENV PATH=/app/.venv/bin:${PATH} \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    OPS_WEB_DIST=/app/static/ops-web

RUN groupadd --gid "${APP_GID}" loanagent \
    && useradd --uid "${APP_UID}" --gid "${APP_GID}" --create-home loanagent

WORKDIR /app
COPY --from=runtime-builder --chown=loanagent:loanagent /app/.venv /app/.venv
COPY --chown=loanagent:loanagent control-plane/src ./src
COPY --from=ops-web-build --chown=loanagent:loanagent /workspace/ops-web/dist /app/static/ops-web

USER loanagent:loanagent
EXPOSE 8000
CMD ["uvicorn", "loanagent.main:app", "--app-dir", "/app/src", "--host", "0.0.0.0", "--port", "8000"]
