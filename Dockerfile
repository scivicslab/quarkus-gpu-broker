FROM eclipse-temurin:21-jre-noble

# The broker is an uber-jar. Per-endpoint overrides (broker.capabilities, internal addresses) are
# not baked in: Quarkus reads config/application.yaml relative to the working directory, so the
# deployment mounts that file at /app/config/application.yaml. broker.nodes comes from the
# environment (BROKER_NODES) for the same reason.
WORKDIR /app
COPY target/quarkus-gpu-broker-*.jar /app/quarkus-gpu-broker.jar

# The status history (broker.history.file) defaults to a path under the home directory; the
# deployment points it at a mounted volume instead. Run unprivileged.
USER 1000

EXPOSE 28005

CMD ["java", "-Dquarkus.http.port=28005", "-jar", "/app/quarkus-gpu-broker.jar"]
