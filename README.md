# The NebulOuS optimizer controller

This module serves two purposes:

- When a new NebulOuS application is started, set up the initial optimizer
  infrastructure, parse the application structure and metric model, and pass
  an initial resource configuration (“Optimized Service Graph”) to the
  Deployment Manager.

- When an application is running, monitor the application-specific metrics
  coming in via ActiveMQ and invoke the optimizer as needed, thereby possibly
  triggering application reconfigurations.

# Building

To compile, install a JDK (Java Development Kit) version 17 or greater on the build machine.

```sh
# Compile:
./gradlew assemble
# Compile and test:
./gradlew build
```

# Building the container

A container can be built and run with the following commands:

```sh
docker build -t optimiser-controller -f optimiser-controller/Dockerfile .
docker run --rm optimiser-controller
```

# Running

To run, install a JRE (Java Runtime Environment) version 17 or greater.

A successful build creates the jar file `dist/optimiser-controller-all.jar`.
This file is self-contained and can be executed via the following command:

```sh
java -jar dist/optimiser-controller-all.jar
```
