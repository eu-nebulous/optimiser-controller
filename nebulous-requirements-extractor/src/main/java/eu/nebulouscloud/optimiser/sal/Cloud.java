package eu.nebulouscloud.optimiser.sal;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
@JsonIgnoreProperties(ignoreUnknown = true)
public class Cloud implements Serializable {
   public static final String JSON_ID = "id";
   public static final String JSON_ENDPOINT = "endpoint";
   public static final String JSON_CLOUD_TYPE = "cloudType";
   public static final String JSON_API = "api";
   public static final String JSON_CREDENTIAL = "credential";
   public static final String JSON_CLOUD_CONFIGURATION = "cloudConfiguration";
   public static final String JSON_OWNER = "owner";
   public static final String JSON_STATE = "state";
   public static final String JSON_DIAGNOSTIC = "diagnostic";
  
   @JsonProperty("id")
   private String id = null;
  
   @JsonProperty("endpoint")
   private String endpoint = null;
   
   @JsonProperty("owner")
   private String owner = null;
  
   @JsonProperty("state")
   private Cloud.StateEnum state = null;
   
   @JsonProperty("diagnostic")
   private String diagnostic = null;


   public String getId() {
      return this.id;
   }

   public String getEndpoint() {
      return this.endpoint;
   }


   public String getOwner() {
      return this.owner;
   }

   public Cloud.StateEnum getState() {
      return this.state;
   }

   public String getDiagnostic() {
      return this.diagnostic;
   }

   public void setId(String id) {
      this.id = id;
   }

   public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
   }


   public void setOwner(String owner) {
      this.owner = owner;
   }

   public void setState(Cloud.StateEnum state) {
      this.state = state;
   }

   public void setDiagnostic(String diagnostic) {
      this.diagnostic = diagnostic;
   }


   public static enum StateEnum {
      OK("OK"),
      ERROR("ERROR");

      private final String value;

      private StateEnum(String value) {
         this.value = value;
      }

      @JsonValue
      public String toString() {
         return this.value;
      }

      @JsonCreator
      public static Cloud.StateEnum fromValue(String text) {
         Cloud.StateEnum[] var1 = values();
         int var2 = var1.length;

         for(int var3 = 0; var3 < var2; ++var3) {
            Cloud.StateEnum b = var1[var3];
            if (b.value.equalsIgnoreCase(text)) {
               return b;
            }
         }

         return null;
      }
   }
}