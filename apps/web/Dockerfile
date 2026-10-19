# The Trackline web application as a container image.
#
#   docker build --build-arg NEXT_PUBLIC_API_BASE_URL=https://api.example.test -t trackline-web apps/web
#
# NEXT_PUBLIC_API_BASE_URL is where a browser reaches the API, and Next.js writes it into the page at build time, so it is a
# build argument and an image is built for one place. API_INTERNAL_URL, read when the server runs, is where the web
# application's own server reaches the API, which on a private network is not the same address.

FROM node:24-slim AS deps
RUN corepack enable
WORKDIR /app
# The lockfile and manifests alone, so the dependencies are downloaded again only when they change.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile

FROM deps AS build
COPY . .
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL} \
    NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

FROM node:24-slim AS runtime
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin trackline
# The standalone server carries only the files it traces, and the static assets sit beside it.
COPY --from=build --chown=trackline /app/.next/standalone ./
COPY --from=build --chown=trackline /app/.next/static ./.next/static
COPY --from=build --chown=trackline /app/public ./public
USER trackline
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD node -e "fetch('http://localhost:3000/').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"
CMD ["node", "server.js"]
