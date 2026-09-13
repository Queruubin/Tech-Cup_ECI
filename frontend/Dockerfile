# Build stage: produces the static bundle in /app/dist.
FROM node:22-slim AS build
WORKDIR /app
RUN npm install -g pnpm@10.11.0

# Install dependencies in their own layer so code changes do not reinstall them.
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# Runtime stage: nginx serves the bundle and proxies /api to the backend container,
# so the browser talks to a single origin and no API URL has to be baked into the build.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
