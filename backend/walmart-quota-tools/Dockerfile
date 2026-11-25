ARG JAVA_RUNTIME=eclipse-temurin:8-jre
FROM ${JAVA_RUNTIME}
WORKDIR /tools
COPY target/walmart-quota-tools-1.0.0.jar /tools/quota-tools.jar
ENTRYPOINT ["java", "-jar", "/tools/quota-tools.jar"]
CMD []
