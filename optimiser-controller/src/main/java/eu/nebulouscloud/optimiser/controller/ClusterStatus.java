package eu.nebulouscloud.optimiser.controller;


public enum ClusterStatus {
   DEFINED("Defined"),
   DEPLOYED("Deployed"),
   FAILED("Failed"),
   SUBMITTED("Submitted"),
   SCALING("Scaling"),
   OTHER("other");

   private final String value;

   private ClusterStatus(String value) {
      this.value = value;
   }

   public String toString() {
      return this.value;
   }

   public static ClusterStatus fromValue(String text) {
      ClusterStatus[] var1 = values();
      int var2 = var1.length;

      for(int var3 = 0; var3 < var2; ++var3) {
         ClusterStatus type = var1[var3];
         if (type.value.equalsIgnoreCase(text)) {
            return type;
         }
      }

      return OTHER;
   }
}