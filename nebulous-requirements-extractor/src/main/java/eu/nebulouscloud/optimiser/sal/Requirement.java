package eu.nebulouscloud.optimiser.sal;

 
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonTypeInfo(
   use = Id.NAME,
   property = "type",
   visible = true
)
@JsonSubTypes({@Type(
   value = AttributeRequirement.class,
   name = "AttributeRequirement"
), @Type(
   value = NodeTypeRequirement.class,
   name = "NodeTypeRequirement"
)})
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Requirement {
   public static final String JSON_TYPE = "type";
   @JsonProperty("type")
   protected Requirement.RequirementType type;

   public Requirement.RequirementType getType() {
      return this.type;
   }

   public void setType(Requirement.RequirementType type) {
      this.type = type;
   }


   public static enum RequirementType {
      ATTRIBUTE("AttributeRequirement"),
      NODE_TYPE("NodeTypeRequirement");

      private final String value;

      private RequirementType(String value) {
         this.value = value;
      }

      @JsonValue
      public String toString() {
         return String.valueOf(this.value);
      }

      @JsonCreator
      public static Requirement.RequirementType fromValue(String text) {
         Requirement.RequirementType[] var1 = values();
         int var2 = var1.length;

         for(int var3 = 0; var3 < var2; ++var3) {
            Requirement.RequirementType b = var1[var3];
            if (String.valueOf(b.value).equals(text.toUpperCase(Locale.ROOT))) {
               return b;
            }
         }

         return null;
      }
   }

   @Override
   public String toString() {
      return "Requirement{" +
             "type=" + type +
             '}';
   }
}