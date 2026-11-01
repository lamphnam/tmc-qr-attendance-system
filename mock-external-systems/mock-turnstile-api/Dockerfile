FROM node:22-alpine
WORKDIR /app
COPY package.json server.js ./
ENV NODE_ENV=production PORT=8090
USER node
EXPOSE 8090
CMD ["node", "server.js"]
