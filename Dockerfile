FROM node:22-alpine AS deps

WORKDIR /app
COPY package*.json ./
RUN npm ci

FROM deps AS build

COPY . .
RUN npm run build

FROM node:22-alpine AS runtime

ENV NODE_ENV=production
ENV PORT=8080

WORKDIR /app
COPY server.mjs ./
COPY --from=build /app/dist ./dist

EXPOSE 8080
CMD ["node", "server.mjs"]
