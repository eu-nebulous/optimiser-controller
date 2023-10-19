# Illustrative model for the 'face-detection' component
#
# Component resource requirements
# Values in meaningful units

var faceDetection.edge.cpu in interval [1.2, 3.0];
var faceDetection.edge.memory in integer [250, 1000];
var faceDetection.cloud.cpu in interval [3.0, 6.0];
var faceDetection.cloud.memory in integer [1000, 4000];

# 
# Cloud and edge providers
#

set CloudProviders := AWS Google Azure;
set EdgeProviders := TID, Orange, Vodaphone, Swisscom;

#
# Number of workers to deploy and at different locations
#

var faceDetection.cloudWorkers.count in integer [2, 10];
var faceDetection.edgeWorkers.count in integer [0, 5];

var faceDetection.cloudWorkers.location{p in CloudProviders} in integer [0, faceDetection.cloudWorkers.count];
var faceDetection.edgeWorkers.location{p in EdgeProviders} in integer [0, faceDetection.edgeWorkers.count];

#
# Making sure to deploy correct number of workers over all locations
#

subject to CloudWorkerLimit :
    sum{ p in CloudProviders } faceDetection.cloudWorkers.location[p] <= faceDetection.cloudWorkers.count;

subject to EdgeWorkerLimit :
    sum{ p in EdgeProviders } faceDetection.edgeWorkers.location[p] <= faceDetection.edgeWorkers.count;
    
#
# Label the nodes at each provider the range is set so that there are as many nodes as 
# there are workers at each provider to accomodate the case where there is only one worker per node.
#

param CloudNodeIDs{p in CloudProviders, 1..faceDetection.cloudWorkers.location[p]};
param EdgeNodeIDs{p in EdgeProviders, 1..faceDetection.edgeWorkers.location[p]};

#
# Specific deployment decision variables with the constraint that the sum of nodes on each provider matches the 
# sum of all providers
#

var faceDetection.cloudWorkers.cloud.node.instances{p in CloudProviders, 1..faceDetection.cloudWorkers.location[p]} 
    in integer [0, faceDetection.cloudWorkers.location[p]];
var faceDetection.edgeWorkers.cloud.node.instances{p in EdgeProviders, 1..faceDetection.edgeWorkers.location[p]} 
    in integer[0, faceDetection.edgeWorkers.location[p]];

    
subject to CloudNodeWorkerLimit:
    sum{ p in CloudProviders, id in integer [1, faceDetection.cloudWorkers.location[p] } 
        faceDetection.cloudWorkers.cloud.node.instances[p, id] == faceDetection.cloudWorkers.location[p];
        
subject to EdgeNodeWorkerLimit:
    sum{ p in EdgeProviders, id in integer [1, faceDetection.edgeWorkers.location[p] } 
        faceDetection.edgeWorkers.edge.node.instances[p, id] == faceDetection.edgeWorkers.location[p];
    
#
# Cost parameters to be set for the available node candidates
# Values in some currency
#

param CloudNodeCost{ id in CloudNodeIDs };
param EdgeNodeCost{ id in EdgeNodeIDs };

#
# Then calculate the total deployment cost for Cloud and Edge
# 

param TotalProviderCloudCost{ p in CloudProviders } 
    = sum{ n in	faceDetection.cloudWorkers.location[p] :
           	faceDetection.cloudWorkers.cloud.node.instances[ p, n ]>0 } ( CloudNodeCost[ CloudNodeIDs[p, n] ] );

param TotalProviderEdgeCost{ p in EdgeProviders } 
    = sum{ n in 	faceDetection.edgeWorkers.location[p] : 
 	faceDetection.edgeWorkers.edge.node.instances[ p, n ]>0 }( EdgeNodeCost[ EdgeNodeIDs[p, n] ] );   
   
#
# Cost constraint on the number of workers
#

param DeploymentBudget;

param TotalCloudCost = sum{ p in CloudProviders } TotalProviderCloudCost[p];
param TotalEdgeCost =  sum{ p in EdgeProviders } TotalProviderEdgeCost[p];

subject to DeploymenCostConstraint :
   TotalCloudCost + TotalEdgeCost <= DeploymentBudget;
  
# =======================================================================================================================================================
# Utility calculation
#
# There will be two objectives for this deployment. 
# The first objective aims at minimising the total cost of the deployment.

minimize Cost:
   TotalCloudCost + TotalEdgeCost;
   
#
# The second objective aims to provide enough facial detection components to be able to process the average number of images. It is assumed that the 
# metric model contains two measurements indicating the number of images to be processed over the next time interval, and the statistical observation 
# of the upper quantile of the obeserved image processing time for the done images. This allows the computation of the number of images per 
# facial detection compoent.  One may always ensure that all images will be processed by overprovisioning facial 
# detection component, but this will contradict minimising cost. At the same time, too few facial detection components will make the queue grow 
# unbounded. The idea is therefore to use a performance utility which will be maximum when the expected flow of images is served.

param ImagesToProcess;
param UpperQuantileImagesProcessingTime;
param TimeIntervalLength = 60s;
param UpperQuantileNoImagesPerComponent = TimeIntervalLength / UpperQuantileImagesProcessingTime;

maximize Performance:
    1/exp( (ImagesToProcess - UpperQuantileNoImagesPerComponent * (faceDetection.cloudWorkers.count + faceDetection.edgeWorkers.count) )^2 );

