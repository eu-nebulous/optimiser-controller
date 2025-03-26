# AMPL file for application 'fireCOAT 2025-03-25 01' with id 03f9b974-d715-477a-971f-512fbad900b4

# Variables
var spec_components_4_properties_traits_0_properties_replicas integer >= 1.0, <= 4.0 := 1;

# Metrics.  Note that we only emit metrics that are in use.  Values will be provided by the solver.
## Raw metrics
param messages_per_minute;	# messages_per_minute
## Composite metrics
param messages_per_minute_promille;	# messages_per_minute_promille

# Constants
param replicas;

# Performance indicators = composite metrics that have at least one variable in their formula
var loadfactor;
# Performance indicator formulas
subject to define_loadfactor : loadfactor = messages_per_minute / spec_components_4_properties_traits_0_properties_replicas;

# Cost parameters - for all components, and use of node-candidates tensor
# Utility functions
maximize uf :
	exp( -(messages_per_minute_promille/spec_components_4_properties_traits_0_properties_replicas -1)^2 );

# Default utility function: specified in message to solver

# Constraints extracted from `/sloViolations`. For these we don't have name from GUI, must be created
# For the solver, SLOs must be negated and operators must be >=, <= (not '>', '<')
subject to constraint_0 : (loadfactor <= 1000.0);
# Constraints specified with `type: constraint` in `/utilityFunctions`
