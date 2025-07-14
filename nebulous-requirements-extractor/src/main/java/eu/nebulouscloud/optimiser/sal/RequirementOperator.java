package eu.nebulouscloud.optimiser.sal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum RequirementOperator {
   EQ("EQ"),
   LEQ("LEQ"),
   GEQ("GEQ"),
   GT("GT"),
   LT("LT"),
   NEQ("NEQ"),
   IN("IN"),
   INC("INC");

   private String value;

   private RequirementOperator(String value) {
      this.value = value;
   }

}