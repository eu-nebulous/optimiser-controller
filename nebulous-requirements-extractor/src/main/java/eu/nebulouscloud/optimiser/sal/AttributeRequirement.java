package eu.nebulouscloud.optimiser.sal;


import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;


@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeName("AttributeRequirement")
public class AttributeRequirement extends Requirement {
   public static final String CLASS_NAME = "AttributeRequirement";
   public static final String JSON_REQUIREMENT_CLASS = "requirementClass";
   public static final String JSON_REQUIREMENT_ATTRIBUTE = "requirementAttribute";
   public static final String JSON_REQUIREMENT_OPERATOR = "requirementOperator";
   public static final String JSON_VALUE = "value";
   @JsonProperty("requirementClass")
   private String requirementClass;
   @JsonProperty("requirementAttribute")
   private String requirementAttribute;
   @JsonProperty("requirementOperator")
   private RequirementOperator requirementOperator;
   @JsonProperty("value")
   private String value;

   public AttributeRequirement() {
      this.type = RequirementType.ATTRIBUTE;
   }

   public AttributeRequirement(String requirementClass, String requirementAttribute, RequirementOperator requirementOperator, String value) {
      this.type = RequirementType.ATTRIBUTE;
      this.requirementClass = requirementClass;
      this.requirementAttribute = requirementAttribute;
      this.requirementOperator = requirementOperator;
      this.value = value;
   }

  

   public String getRequirementClass() {
      return this.requirementClass;
   }

   public String getRequirementAttribute() {
      return this.requirementAttribute;
   }

   public RequirementOperator getRequirementOperator() {
      return this.requirementOperator;
   }

   public String getValue() {
      return this.value;
   }

   public void setRequirementClass(String requirementClass) {
      this.requirementClass = requirementClass;
   }

   public void setRequirementAttribute(String requirementAttribute) {
      this.requirementAttribute = requirementAttribute;
   }

   public void setRequirementOperator(RequirementOperator requirementOperator) {
      this.requirementOperator = requirementOperator;
   }

   public void setValue(String value) {
      this.value = value;
   }

	@Override
	public int hashCode() {
		return Objects.hash(requirementAttribute, requirementClass, requirementOperator, value);
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		AttributeRequirement other = (AttributeRequirement) obj;
		return Objects.equals(requirementAttribute, other.requirementAttribute)
				&& Objects.equals(requirementClass, other.requirementClass)
				&& requirementOperator == other.requirementOperator && Objects.equals(value, other.value);
	}
	   
	   

   @Override
   public String toString() {
      return "AttributeRequirement{" +
             "type=" + type +
             ", requirementClass='" + requirementClass + '\'' +
             ", requirementAttribute='" + requirementAttribute + '\'' +
             ", requirementOperator=" + requirementOperator +
             ", value='" + value + '\'' +
             '}';
   }


}