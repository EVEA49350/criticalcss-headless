FROM mcr.microsoft.com/playwright:v1.43.0-jammy

WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm ci || npm install

COPY server.js ./

ARG CAPROVER_GIT_COMMIT_SHA
ENV APP_GIT_SHA=$CAPROVER_GIT_COMMIT_SHA

ENV NODE_ENV=production
ENV PORT=3000

EXPOSE 3000

CMD ["node", "server.js"]
