/**
 * This package implements the NebulOuS optimiser controller.
 *
 * <p>The optimiser-controller keeps track of all NebulOuS applications
 * running, and controls deployment and redeployment.  For initial deployment,
 * the optimiser-controller calculates the node requirements, negotiates node
 * candidates with the resource boker and instructs the deployment manager to
 * create an application cluster.  For redeployment, the optimiser-controller
 * listens to solutions from the per-application solver, calculates the new
 * deployment scenario, and redeploys the application by instructing the
 * deployment manager to add and remove nodes from the application cluster as
 * needed.
 *
 * <p>The two main entry points are the classes {@link Main} (for deployment)
 * and {@link LocalExecution} (for local testing).  The class {@link
 * NebulousApp} implements the per-applicatin state.  For reasons of per-class
 * code size, some methods are implemented in static classes like {@link
 * AMPLGenerator} and {@link NebulousAppDeployer}.  The class {@link
 * ExnConnector} implements communication with the rest of the system by
 * setting up the necessary publishers and message handlers to send and
 * receive ActiveMQ messages.
 *
 * @author Rudolf Schlatte
 */
package eu.nebulouscloud.optimiser.controller;
