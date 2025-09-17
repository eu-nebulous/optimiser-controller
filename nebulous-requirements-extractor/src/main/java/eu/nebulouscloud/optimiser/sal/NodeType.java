package eu.nebulouscloud.optimiser.sal;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public enum NodeType {
   IAAS(0, "IAAS", "IAAS"),
   PAAS(1, "PAAS", "PAAS"),
   FAAS(2, "FAAS", "FAAS"),
   BYON(3, "BYON", "BYON"),
   EDGE(4, "EDGE", "EDGE"),
   SIMULATION(5, "SIMULATION", "SIMULATION");

   public static final int IAAS_VALUE = 0;
   public static final int PAAS_VALUE = 1;
   public static final int FAAS_VALUE = 2;
   public static final int BYON_VALUE = 3;
   public static final int EDGE_VALUE = 4;
   public static final int SIMULATION_VALUE = 5;
   private static final NodeType[] VALUES_ARRAY = new NodeType[]{IAAS, PAAS, FAAS, BYON, EDGE, SIMULATION};
   public static final List<NodeType> VALUES = Collections.unmodifiableList(Arrays.asList(VALUES_ARRAY));
   private final int value;
   private final String name;
   private final String literal;

   public static NodeType get(String literal) {
      for(int i = 0; i < VALUES_ARRAY.length; ++i) {
         NodeType result = VALUES_ARRAY[i];
         if (result.toString().equals(literal)) {
            return result;
         }
      }

      return null;
   }

   public static NodeType getByName(String name) {
      for(int i = 0; i < VALUES_ARRAY.length; ++i) {
         NodeType result = VALUES_ARRAY[i];
         if (result.getName().equals(name)) {
            return result;
         }
      }

      return null;
   }

   public static NodeType get(int value) {
      switch(value) {
      case 0:
         return IAAS;
      case 1:
         return PAAS;
      case 2:
         return FAAS;
      case 3:
         return BYON;
      case 4:
         return EDGE;
      case 5:
         return SIMULATION;
      default:
         return null;
      }
   }

   private NodeType(int value, String name, String literal) {
      this.value = value;
      this.name = name;
      this.literal = literal;
   }

   public int getValue() {
      return this.value;
   }

   public String getName() {
      return this.name;
   }

   public String getLiteral() {
      return this.literal;
   }

   public String toString() {
      return this.literal;
   }
}