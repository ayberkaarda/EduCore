# syntax=docker/dockerfile:1.7
#
# EduCore frontend image: `npm ci` build, then static files served by unprivileged nginx (uid 101, port 8080),
# which also proxies /api/ to the educore-backend service (nginx.conf).
# Base images are pinned by digest; Dependabot (.github/dependabot.yml) proposes digest updates weekly.

FROM node:26-alpine@sha256:0b36e8c136b94cd4fcf02188228e76c31ad5872eef3fec8cbd2eee500cfd9e80 AS build
WORKDIR /app
# Build as the image's non-root `node` user.
RUN chown node:node /app
USER node

COPY --chown=node:node package.json package-lock.json ./
RUN --mount=type=cache,target=/home/node/.npm,uid=1000,gid=1000 npm ci --no-audit --no-fund

COPY --chown=node:node . .

# The public pages are prerendered from the public API at build time (docs/seo/BUILD.md, docs/ops/CI.md):
#   BUILD_SCRIPT=build       real catalog: PUBLIC_API_URL (origin serving /api/v1/public, e.g. https://host) and
#                            PUBLIC_SITE_URL (the public origin = EDUCORE_SEO_BASE_URL) are required; the build
#                            fails when the API is unreachable or no course is published (no empty catalog ships).
#   BUILD_SCRIPT=build:mock  bundled mock catalog, for CI checks, local development and image scans only;
#                            never for a production deployment (docker-compose.prod.yml fixes BUILD_SCRIPT=build).
ARG BUILD_SCRIPT=build
ARG PUBLIC_API_URL=""
ARG PUBLIC_SITE_URL=""
# Output in dist/client: prerendered pages (Turkish at /, English under /en), 404.html, robots.txt,
# llms*.txt, sitemap-static.xml, __spa-fallback.html (shell for /app/**) and content-hashed assets/.
RUN case "$BUILD_SCRIPT" in \
      build|build:mock) ;; \
      *) echo "BUILD_SCRIPT must be build or build:mock (got '$BUILD_SCRIPT')" >&2; exit 1 ;; \
    esac \
    && npm run "$BUILD_SCRIPT" \
    && mv dist/client /app/site

FROM nginxinc/nginx-unprivileged:1.30-alpine@sha256:ed04ec1ff34502c339ee5c3ae3f855442398edc1d05591e2b98981dcbbd20b1e
# Apply Alpine security fixes published after the pinned base digest (the CI image scan fails on
# fixable HIGH/CRITICAL findings); the image drops back to the unprivileged user below.
USER root
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/site /usr/share/nginx/html

# nginx-unprivileged runs as uid 101 and cannot bind ports below 1024.
USER 101
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
