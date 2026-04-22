# ── Build stage ───────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --silent
COPY . .
RUN npx ng build --configuration production --base-href /dashboard/

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM nginx:alpine
COPY --from=builder /app/dist/ecom-dashboard/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
