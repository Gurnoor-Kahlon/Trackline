# The Trackline API as a container image: built here, run on a JRE only.
#
#   docker build -t trackline-api services/api
#
# Two stages, so the image carries a runtime and one jar and not the JDK, Maven or the build cache. The Maven wrapper
# downloads a pinned, checksummed Maven, and the build downloads the protobuf compiler for the GTFS-Realtime schema, so the
# build needs the network and the run does not.

FROM eclipse-temurin:24-jdk AS build
WORKDIR /build

# The Maven wrapper checks its download against the checksum of the .zip pinned in .mvn/wrapper, and with no unzip on the
# machine it switches to the .tar.gz, which has another checksum and so fails the check. Providing unzip keeps the pin.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

# Dependencies first and on their own layer, so a change to the source does not download them again. The wrapper is run through
# sh because a checkout made on Windows does not mark it executable, and a build from such a checkout should not depend on that.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI, against real PostgreSQL and Redis, which a build container does not have.
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q -DskipTests package \
    && cp target/trackline-api-*.jar /build/app.jar

FROM eclipse-temurin:24-jre AS runtime

# Not root: nothing here needs it, and a flaw in the application should not be a flaw in the container.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin trackline
WORKDIR /app
COPY --from=build --chown=trackline /build/app.jar /app/app.jar
USER trackline

# Sized from the container's own memory limit rather than the host's, and stopped outright if it runs out, so the
# orchestrator restarts a clean process instead of leaving one that is thrashing.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent http://localhost:8080/actuator/health > /dev/null || exit 1

# Arguments go to the application: `docker run ... trackline-api --import-gtfs` imports the schedule and exits.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
