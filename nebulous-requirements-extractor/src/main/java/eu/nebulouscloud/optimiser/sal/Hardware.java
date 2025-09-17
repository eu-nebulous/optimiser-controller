package eu.nebulouscloud.optimiser.sal;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
@JsonIgnoreProperties(ignoreUnknown = true)
public class Hardware implements Serializable {
   public static final String JSON_ID = "id";
   public static final String JSON_NAME = "name";
   public static final String JSON_PROVIDER_ID = "providerId";
   public static final String JSON_CORES = "cores";
   public static final String JSON_CPU_FREQUENCY = "cpuFrequency";
   public static final String JSON_RAM = "ram";
   public static final String JSON_DISK = "disk";
   public static final String JSON_FPGA = "fpga";
   public static final String JSON_GPU = "gpu";
   public static final String JSON_LOCATION = "location";
   public static final String JSON_STATE = "state";
   public static final String JSON_OWNER = "owner";
  
   @JsonProperty("id")
   private String id = null;
   
   @JsonProperty("name")
   private String name = null;
  
   @JsonProperty("providerId")
   private String providerId = null;
  
   @JsonProperty("cores")
   private Integer cores = null;
   
   @JsonProperty("cpuFrequency")
   private Double cpuFrequency = null;
   
   @JsonProperty("ram")
   private Long ram = null;
   
   @JsonProperty("disk")
   private Double disk = null;
   
   @JsonProperty("fpga")
   private Integer fpga = null;
   
   @JsonProperty("gpu")
   private Integer gpu = null;
   
   @JsonProperty("location")
   private Location location = null;
   
   @JsonProperty("state")
   private DiscoveryItemState state = null;
   
   
   public Hardware() {
   }

   public String getId() {
      return this.id;
   }

   public String getName() {
      return this.name;
   }

   public String getProviderId() {
      return this.providerId;
   }

   public Integer getCores() {
      return this.cores;
   }

   public Double getCpuFrequency() {
      return this.cpuFrequency;
   }

   public Long getRam() {
      return this.ram;
   }

   public Double getDisk() {
      return this.disk;
   }

   public Integer getFpga() {
      return this.fpga;
   }

   public Integer getGpu() {
      return this.gpu;
   }

   public Location getLocation() {
      return this.location;
   }

   public DiscoveryItemState getState() {
      return this.state;
   }

   
}