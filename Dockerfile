FROM node:22-bookworm-slim AS FRONTEND_BUILD
WORKDIR /build
COPY package.json yarn.lock ./
RUN corepack enable && yarn install --frozen-lockfile --ignore-scripts --non-interactive
COPY gulpfile.js ./
COPY scripts/fetch-geoip.mjs ./scripts/
COPY src/main/webapp ./src/main/webapp
RUN yarn build && node scripts/fetch-geoip.mjs

FROM maven:3.9-eclipse-temurin-17 AS MVN_BUILD

WORKDIR /opt/bolo/
ADD . /tmp
COPY --from=FRONTEND_BUILD /build/src/main/webapp /tmp/src/main/webapp
COPY --from=FRONTEND_BUILD /build/src/main/distribution/geoip /tmp/src/main/distribution/geoip
RUN cd /tmp && mvn --batch-mode package -Pci -Dlicense.skip=true && cd /opt/bolo && jar -xf /tmp/target/bolo-netty.zip \
    && cp -f /tmp/src/main/resources/docker/* /opt/bolo/WEB-INF/classes/

FROM eclipse-temurin:17-jre-jammy
LABEL maintainer="Liang Ding<d@b3log.org>"

WORKDIR /opt/bolo/
COPY --from=MVN_BUILD /opt/bolo/ /opt/bolo/

ENV TZ=Asia/Shanghai
EXPOSE 8080

ENTRYPOINT [ "java", "-Dfile.encoding=UTF-8", "-cp", "WEB-INF/classes:WEB-INF/lib/*:.", "org.b3log.solo.Starter" ]
