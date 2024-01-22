var face_detection_edge_worker_cpu >= 1.2, <= 3.0;
var face_detection_edge_worker_memory >= 250.0, <= 1000.0;
var face_detection_edge_worker_count integer >= 0, <= 5;
# TODO generate entries for map 'face_detection_edge_workers' -> I think it's not needed
var face_detection_cloud_worker_cpu >= 3.0, <= 6.0;
var face_detection_cloud_worker_memory >= 1000.0, <= 4000.0;
var face_detection_cloud_worker_count integer >= 2, <= 10;
# TODO generate entries for map 'face_detection_cloud_workers'

#Raw and composite metrics that are independent from running configuration. The values will be provided by the solver.
param TotalCoresUsed;
param AvgResponseTime;


#Performance indicators = composite metrics that have at least one variable in their formula
param TotalCoresUsedFraction = TotalCoresUsed/(face_detection_edge_worker_cpu*face_detection_edge_worker_count+face_detection_cloud_worker_cpu*face_detection_cloud_worker_count);
param AvgResponseTimePerComponent = AvgResponseTime/(face_detection_edge_worker_count+face_detection_cloud_worker_count);

# Composite metrics
# TODO: here we should also have initial values!
param AvgResponseTime;  # AvgResponseTime
param TotalCoresUsed;   # TotalCoresUsed

# Performance indicators = composite metrics that have at least one variable in their formula
# TotalCoresUsedFraction : A/(B*C)
param TotalCoresUsedFraction = TotalCoresUsed/(face_detection_cloud_worker_count*face_detection_cloud_worker_cpu);
# AvgResponseTimePerComponent : A/B
param AvgResponseTimePerComponent = AvgResponseTime/face_detection_cloud_worker_count;

# TBD: cost parameters - for all components! and use of node-candidates tensor

#Utility functions
maximize utility_function_1:
TotalCoresUsedFraction;

minimize utility_function_2:
AvgResponseTimePerComponent;

# Constraints. For constraints we don't have name from GUI, must be created
# TODO: generate from 'slo' hierarchical entry


#Constraints. For constraints we don't have name from GUI, must be created
subject to constraint_1: AvgResponseTimePerComponent < 3600;
subject to constraint_2: TotalCoresUsedFraction > 0.2 and TotalCoresUsedFraction < 0.9;
