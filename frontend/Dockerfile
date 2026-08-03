FROM node:20-alpine AS build
WORKDIR /app
ARG VITE_GATEWAY_URL=http://localhost:8080
ENV VITE_GATEWAY_URL=${VITE_GATEWAY_URL}
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Dev stage (docker compose watch, ADR-0042): the Vite dev server has its
# own HMR, so this stage exists only to give `sync` a container with
# node_modules already installed -- `npm run build` above is skipped
# entirely, it's not needed for `vite dev`. Port pinned to 3000 (Vite's
# default is 5173) to match docker-compose.yml's published port mapping.
FROM node:20-alpine AS dev
WORKDIR /app
ARG VITE_GATEWAY_URL=http://localhost:8080
ENV VITE_GATEWAY_URL=${VITE_GATEWAY_URL}
COPY package*.json ./
RUN npm ci
COPY . .
EXPOSE 3000
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "3000"]

FROM node:20-alpine
WORKDIR /app
COPY --from=build /app/build ./build
COPY --from=build /app/package*.json ./
RUN npm ci --omit=dev

EXPOSE 3000
ENV PORT=3000

CMD ["node", "build"]
