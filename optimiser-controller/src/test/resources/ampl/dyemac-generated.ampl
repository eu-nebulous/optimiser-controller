# AMPL file for application 'DYEMAC Cloud T7 6_2_25' with id e05cfbf0-3f29-40e0-a3db-ebb7fd2732df

# Variables
var spec_components_1_traits_0_properties_replicas integer >= 1, <= 3 := 1;
var spec_components_0_traits_0_properties_replicas integer >= 1, <= 3 := 1;

# Metrics.  Note that we only emit metrics that are in use.  Values will be provided by the solver.
## Raw metrics
## Composite metrics
param sum_cpu_consumption_all;	# sum_cpu_consumption_all
param sum_requests_per_second;	# sum_requests_per_second

# Constants
param dosage_analysis_replica_count_const;
param data_collection_replica_count_const;

# Performance indicators = composite metrics that have at least one variable in their formula
var mean_cpu_consumption_all;
var mean_requests_per_second;
# Performance indicator formulas
subject to define_mean_cpu_consumption_all : mean_cpu_consumption_all = sum_cpu_consumption_all/dosage_analysis_replica_count_const;
subject to define_mean_requests_per_second : mean_requests_per_second = sum_requests_per_second/data_collection_replica_count_const;

# Cost parameters - for all components, and use of node-candidates tensor
# Utility functions
maximize test_utility :
	0.5*exp(-20*(sum_cpu_consumption_all-80*spec_components_1_traits_0_properties_replicas)^2)+0.5*exp(-0.12*(sum_requests_per_second-8*spec_components_0_traits_0_properties_replicas)^2);

# Default utility function: specified in message to solver

# Constraints extracted from `/sloViolations`. For these we don't have name from GUI, must be created
# For the solver, SLOs must be negated and operators must be >=, <= (not '>', '<')
subject to constraint_0 : mean_cpu_consumption_all >= -1000000000;
subject to constraint_1 : mean_cpu_consumption_all <= 70;
subject to constraint_2 : mean_requests_per_second <= 8;
subject to constraint_3 : mean_requests_per_second >= -1000000000;
# Constraints specified with `type: constraint` in `/utilityFunctions`
