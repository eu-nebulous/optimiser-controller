package eu.nebulouscloud.optimiser.sal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public enum DiscoveryItemState {
   NEW("NEW"),
   OK("OK"),
   REMOTELY_DELETED("REMOTELY_DELETED"),
   LOCALLY_DELETED("LOCALLY_DELETED"),
   DISABLED("DISABLED"),
   DELETED("DELETED"),
   UNKNOWN("UNKNOWN");

   private String value;

   private DiscoveryItemState(String value) {
      this.value = value;
   }

   @JsonValue
   public String toString() {
      return String.valueOf(this.value);
   }

   @JsonCreator
   public static DiscoveryItemState fromValue(String text) {
      DiscoveryItemState[] var1 = values();
      int var2 = var1.length;

      for(int var3 = 0; var3 < var2; ++var3) {
         DiscoveryItemState b = var1[var3];
         if (String.valueOf(b.value).equals(text.toUpperCase(Locale.ROOT))) {
            return b;
         }
      }

      return null;
   }
}