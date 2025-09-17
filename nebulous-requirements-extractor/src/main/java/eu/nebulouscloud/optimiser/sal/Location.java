package eu.nebulouscloud.optimiser.sal;

import java.io.Serializable;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
@JsonIgnoreProperties(ignoreUnknown = true)
public class Location implements Serializable {
   public static final String JSON_ID = "id";
   public static final String JSON_NAME = "name";
   public static final String JSON_PROVIDER_ID = "providerId";
   public static final String JSON_LOCATION_SCOPE = "locationScope";
   public static final String JSON_IS_ASSIGNABLE = "isAssignable";
   public static final String JSON_GEO_LOCATION = "geoLocation";
   public static final String JSON_PARENT = "parent";
   public static final String JSON_STATE = "state";
   public static final String JSON_OWNER = "owner";
  
   @JsonProperty("id")
   private String id = null;
  
   @JsonProperty("name")
   private String name = null;
   
   @JsonProperty("providerId")
   private String providerId = null;
   
   @JsonProperty("locationScope")
   private Location.LocationScopeEnum locationScope = null;
  
   @JsonProperty("isAssignable")
   private Boolean isAssignable = null;
  
   @JsonProperty("geoLocation")
   private GeoLocation geoLocation = null;
   
   @JsonProperty("parent")
   private Location parent = null;
  
   @JsonProperty("state")
   private DiscoveryItemState state = null;
  
   @JsonProperty("owner")
   private String owner = null;

   public Location() {
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

   public Location.LocationScopeEnum getLocationScope() {
      return this.locationScope;
   }

   public Boolean getIsAssignable() {
      return this.isAssignable;
   }

   public GeoLocation getGeoLocation() {
      return this.geoLocation;
   }

   public Location getParent() {
      return this.parent;
   }

   public DiscoveryItemState getState() {
      return this.state;
   }

   public String getOwner() {
      return this.owner;
   }

   public Location setId(String id) {
      this.id = id;
      return this;
   }

   public Location setName(String name) {
      this.name = name;
      return this;
   }

   public Location setProviderId(String providerId) {
      this.providerId = providerId;
      return this;
   }

   public Location setLocationScope(Location.LocationScopeEnum locationScope) {
      this.locationScope = locationScope;
      return this;
   }

   public Location setIsAssignable(Boolean isAssignable) {
      this.isAssignable = isAssignable;
      return this;
   }

   public Location setGeoLocation(GeoLocation geoLocation) {
      this.geoLocation = geoLocation;
      return this;
   }

   public Location setParent(Location parent) {
      this.parent = parent;
      return this;
   }

   public Location setState(DiscoveryItemState state) {
      this.state = state;
      return this;
   }

   public Location setOwner(String owner) {
      this.owner = owner;
      return this;
   }

   public static enum LocationScopeEnum {
      PROVIDER("PROVIDER"),
      REGION("REGION"),
      ZONE("ZONE"),
      HOST("HOST");

      private final String value;

      private LocationScopeEnum(String value) {
         this.value = value;
      }

      @JsonValue
      public String toString() {
         return String.valueOf(this.value);
      }

      @JsonCreator
      public static Location.LocationScopeEnum fromValue(String text) {
         Location.LocationScopeEnum[] var1 = values();
         int var2 = var1.length;

         for(int var3 = 0; var3 < var2; ++var3) {
            Location.LocationScopeEnum b = var1[var3];
            if (String.valueOf(b.value).equals(text.toUpperCase(Locale.ROOT))) {
               return b;
            }
         }

         return null;
      }
   }
}