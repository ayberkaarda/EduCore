# 1. Aşama: Node.js 22 ile projeyi derleme (Build)
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
# Vite ile projeyi derle
RUN npm run build

# 2. Aşama: Nginx ile sunma
FROM nginx:alpine
# Nginx ayar dosyasını kopyala
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Derlenen statik dosyaları Nginx'in yayın klasörüne kopyala
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]