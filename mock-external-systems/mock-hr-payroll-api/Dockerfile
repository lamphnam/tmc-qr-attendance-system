FROM node:22-alpine
WORKDIR /app
COPY package.json server.js ./
# /data holds the optional persisted state (compose mounts the `mock-hr-data` volume here).
RUN mkdir -p /data && chown node:node /data
ENV NODE_ENV=production PORT=8091 HR_DATA_FILE=/data/hr-state.json
USER node
EXPOSE 8091
CMD ["node", "server.js"]
