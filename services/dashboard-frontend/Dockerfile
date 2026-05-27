FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install --silent
COPY public/ public/
COPY src/ src/
COPY tsconfig.json .
ENV REACT_APP_WS_SUFFIX=/ws/metrics
RUN npm run build

FROM nginx:1.25-alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY --from=build /app/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Run as non-root for OpenShift
RUN chmod -R 777 /var/cache/nginx /var/run /var/log/nginx
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
