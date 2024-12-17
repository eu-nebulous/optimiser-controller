# Variables
var spec_components_0_traits_0_properties_replicas integer >= 1, <= 5 := 1;

# Metrics.  Note that we only emit metrics that are in use.  Values will be provided by the solver.\n
## Raw metrics\n
param AccumulatedSecondsPendingRequests;

## Composite metrics\n\n
# Constants\n\n
# Performance indicators = composite metrics that have at least one variable in their formula\n
# Performance indicator formulas\n\n
# Cost parameters - for all components, and use of node-candidates tensor\n
# Utility functions\n
minimize f :
AccumulatedSecondsPendingRequests/(spec_components_0_traits_0_properties_replicas*100);

# Default utility function: specified in message to solver\n\n# Constraints. For constraints we don't have name from GUI, must be created\n",

subject to example_constraint: AccumulatedSecondsPendingRequests/(spec_components_0_traits_0_properties_replicas*100) - 100 >= 0;
