FROM node:18-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY public/ public/
COPY src/ src/
COPY tsconfig.json .
ENV REACT_APP_WS_SUFFIX=/ws/metrics
RUN npm run build

FROM nginx:1.25-alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY --from=build /app/build /usr/share/nginx/html
# nginx.conf is a template: the image's entrypoint renders it into
# /etc/nginx/conf.d/default.conf at start, substituting only
# ${NGINX_LOCAL_RESOLVERS} (the nameservers from the pod's /etc/resolv.conf).
COPY nginx.conf /etc/nginx/templates/default.conf.template
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
ENV NGINX_ENVSUBST_FILTER=NGINX_LOCAL_RESOLVERS
# Run as non-root for OpenShift (conf.d must be writable for the render step)
RUN chmod -R 777 /var/cache/nginx /var/run /var/log/nginx /etc/nginx/conf.d
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
