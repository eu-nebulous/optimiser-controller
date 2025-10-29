package eu.nebulouscloud.optimiser.sal;


import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeoLocation implements Serializable {
   public static final String JSON_CITY = "city";
   public static final String JSON_COUNTRY = "country";
   public static final String JSON_LATITUDE = "latitude";
   public static final String JSON_LONGITUDE = "longitude";
  
   @JsonProperty("city")
   private String city = null;
  
   @JsonProperty("country")
   private String country = null;
  
   @JsonProperty("latitude")
   private Double latitude = null;
   
   @JsonProperty("longitude")
   private Double longitude = null;


  
   public GeoLocation() {
   }

   public String getCity() {
      return this.city;
   }

   public String getCountry() {
      return this.country;
   }

   public Double getLatitude() {
      return this.latitude;
   }

   public Double getLongitude() {
      return this.longitude;
   }

   public void setCity(String city) {
      this.city = city;
   }

   public void setCountry(String country) {
      this.country = country;
   }

   public void setLatitude(Double latitude) {
      this.latitude = latitude;
   }

   public void setLongitude(Double longitude) {
      this.longitude = longitude;
   }

   @Override
   public String toString() {
      return "GeoLocation{" +
             "city='" + city + '\'' +
             ", country='" + country + '\'' +
             ", latitude=" + latitude +
             ", longitude=" + longitude +
             '}';
   }
}