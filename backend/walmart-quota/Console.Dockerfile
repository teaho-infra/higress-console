# Reuse the existing Console runtime. This does not alter the Higress gateway image.
ARG CONSOLE_BASE=higress-console/console:v2.2.4
FROM ${CONSOLE_BASE}
COPY console/target/higress-console.jar /app/higress-console.jar
