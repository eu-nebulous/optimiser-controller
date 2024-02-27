#
# Build stage
#
FROM docker.io/library/gradle:8-jdk17-alpine AS build
COPY . /home/optimiser-controller
WORKDIR /home/optimiser-controller
RUN gradle --no-daemon -Dorg.gradle.logging.level=info clean build

#
# Package stage
#
FROM docker.io/library/eclipse-temurin:17-jre
COPY --from=build /home/optimiser-controller/optimiser-controller/dist/optimiser-controller-all.jar /usr/local/lib/optimiser-controller-all.jar
ENTRYPOINT ["java","-jar","/usr/local/lib/optimiser-controller-all.jar", "-vv"]
