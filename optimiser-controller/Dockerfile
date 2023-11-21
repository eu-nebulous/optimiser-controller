#
# Build stage
#
FROM docker.io/library/gradle:8-jdk17-alpine AS build
COPY .. /home/optimiser-controller
WORKDIR /home/optimiser-controller
RUN gradle --no-daemon clean build

#
# Package stage
#
FROM docker.io/library/eclipse-temurin:17-jre
COPY --from=build /home/optimiser-controller/optimiser-controller/dist/optimiser-controller-all.jar /usr/local/lib/optimiser-controller-all.jar
# When running the image without arguments, print help for now.  Adjust this
# once we start listening to MQTT messages from the NebulOuS GUI.
CMD ["--help"]
ENTRYPOINT ["java","-jar","/usr/local/lib/optimiser-controller-all.jar"]
