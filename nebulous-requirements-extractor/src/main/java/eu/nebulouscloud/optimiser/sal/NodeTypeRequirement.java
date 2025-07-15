package eu.nebulouscloud.optimiser.sal;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeName("NodeTypeRequirement")
public class NodeTypeRequirement extends Requirement {
   public static final String CLASS_NAME = "NodeTypeRequirement";
   public static final String JSON_NODE_TYPES = "nodeTypes";
   public static final String JSON_JOB_ID_FOR_BYON = "jobIdForBYON";
   public static final String JSON_JOB_ID_FOR_EDGE = "jobIdForEDGE";
   @JsonProperty("nodeTypes")
   private List<NodeType> nodeTypes;
   @JsonProperty("jobIdForBYON")
   private String jobIdForBYON;
   @JsonProperty("jobIdForEDGE")
   private String jobIdForEDGE;

   public NodeTypeRequirement() {
      this.type = RequirementType.NODE_TYPE;
   }

   public NodeTypeRequirement(List<NodeType> nodeTypes, String jobIdForBYON, String jobIdForEDGE) {
      this.type = RequirementType.NODE_TYPE;
      this.nodeTypes = nodeTypes;
      this.jobIdForBYON = jobIdForBYON;
      this.jobIdForEDGE = jobIdForEDGE;
   }


   public List<NodeType> getNodeTypes() {
      return this.nodeTypes;
   }

   public String getJobIdForBYON() {
      return this.jobIdForBYON;
   }

   public String getJobIdForEDGE() {
      return this.jobIdForEDGE;
   }

   public void setNodeTypes(List<NodeType> nodeTypes) {
      this.nodeTypes = nodeTypes;
   }

   public void setJobIdForBYON(String jobIdForBYON) {
      this.jobIdForBYON = jobIdForBYON;
   }

   public void setJobIdForEDGE(String jobIdForEDGE) {
      this.jobIdForEDGE = jobIdForEDGE;
   }
}