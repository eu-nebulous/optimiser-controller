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
cd optimiser-controller
docker build -t optimiser-controller -f Dockerfile .
docker run --rm optimiser-controller
```

# Running

To run, install a JRE (Java Runtime Environment) version 17 or greater.

A successful build creates the jar file `dist/optimiser-controller-all.jar`.
This file is self-contained and can be executed via the following command:

```sh
java -jar dist/optimiser-controller-all.jar
```

## Running in jshell

The command `./gradlew --console plain jshell` opens a REPL (read-eval-print
loop) with the package `eu.nebulous.optimiser.controller` pre-imported.  On
the REPL, one can write Java snippets such as:

```java
SalConnector conn = new SalConnector(new URI("http://localhost:8088"));
conn.isConnected();
```

For command-line editing and history support, use the `rlwrap` command:
`rlwrap ./gradlew --console plain jshell`.
