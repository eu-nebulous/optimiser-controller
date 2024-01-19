# AMPL file for application with id f81ee-b42a8-a13d56-e28ec9-2f5578

# Variables
var face_detection_cloud_worker_cpu >= 3.0, <= 6.0;     # .spec.components[3].properties.cpu
var face_detection_cloud_worker_memory >= 1000.0, <= 4000.0;    # .spec.components[3].properties.memory
var face_detection_cloud_worker_count integer;  # .spec.components[3].traits[1].properties.replicas

# Raw metrics
# TODO: here we should also have initial values!
param CoresUsed;        # CoresUsed
param ResponseTime;     # ResponseTime

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

# Utility functions
# Utility Function 1 : A
minimize utility_function_1 :
        AvgResponseTimePerComponent;
# Utility Function 2 : A
maximize utility_function_2 :
        TotalCoresUsedFraction;

# Default utility function: tbd

# Constraints. For constraints we don't have name from GUI, must be created
# TODO: generate from 'slo' hierarchical entry

