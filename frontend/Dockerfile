FROM node:20-alpine AS build
WORKDIR /app
ARG VITE_GATEWAY_URL=http://localhost:8080
ENV VITE_GATEWAY_URL=${VITE_GATEWAY_URL}
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:20-alpine
WORKDIR /app
COPY --from=build /app/build ./build
COPY --from=build /app/package*.json ./
RUN npm ci --omit=dev

EXPOSE 3000
ENV PORT=3000

CMD ["node", "build"]
