FROM node:22-slim
RUN npm install -g @pen.dev/cli && npm cache clean --force
WORKDIR /work
ENTRYPOINT ["pen"]
