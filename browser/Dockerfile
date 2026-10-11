FROM mcr.microsoft.com/playwright:v1.64.0-noble
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY server.mjs ./
COPY quickjs*.mjs ./
COPY quickjs-runtime/ ./quickjs-runtime/
ENV READER_BROWSER_HOST=0.0.0.0 READER_BROWSER_PORT=8090
EXPOSE 8090
CMD ["node", "server.mjs"]
