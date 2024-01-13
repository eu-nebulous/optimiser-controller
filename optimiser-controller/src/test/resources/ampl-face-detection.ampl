var face_detection_edge_worker_cpu >= 1.2, <= 3.0;
var face_detection_edge_worker_memory >= 250.0, <= 1000.0;
var face_detection_edge_worker_count integer >= 0, <= 5;
var face_detection_cloud_worker_cpu >= 3.0, <= 6.0;
var face_detection_cloud_worker_memory >= 1000.0, <= 4000.0;
var face_detection_cloud_worker_count integer >= 2, <= 10;

#Raw and composite metrics that are independent from running configuration. TODO: here we should also have initial values!
param first_composite_metric;
param average_cpu;
param cpu_util_prtc_2;
param cpu_second_component;

#Performance indicators = composite metrics that have at least one variable in their formula
param first_performance_indicator = (average_cpu + cpu_second_component)/(face_detection_edge_worker_cpu*face_detection_edge_worker_count);


#TBD: cost parameters - for all components! and use of node-candidates tensor
param face_detection_price;


#Utility functions
maximize utility_function_1:
cpu_second_component * average_cpu * cpu_util_prtc_2 - face_detection_edge_worker_count/face_detection_edge_worker_cpu;

minimize utility_function_2:
first_performance_indicator;

#default utility function: tbd


#Constraints. For constraints we don't have name from GUI, must be created
subject to constraint_1: cpu_util_prtc_2 > 2 or cpu_second_component < 100; 
subject to constraint_2: first_performance_indicator > 100;