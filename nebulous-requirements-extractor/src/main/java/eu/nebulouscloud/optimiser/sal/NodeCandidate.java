package eu.nebulouscloud.optimiser.sal;

import java.io.Serializable;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeCandidate implements Serializable {
	public static final String JSON_ID = "id";
	public static final String JSON_NODE_CANDIDATE_TYPE = "nodeCandidateType";
	public static final String JSON_JOB_ID_FOR_BYON = "jobIdForByon";
	public static final String JSON_JOB_ID_FOR_EDGE = "jobIdForEdge";
	public static final String JSON_PRICE = "price";
	public static final String JSON_CLOUD = "cloud";
	public static final String JSON_LOCATION = "location";
	public static final String JSON_IMAGE = "image";
	public static final String JSON_HARDWARE = "hardware";
	public static final String JSON_PRICE_PER_INVOCATION = "pricePerInvocation";
	public static final String JSON_MEMORY_PRICE = "memoryPrice";
	public static final String JSON_NODE_ID = "nodeId";
	public static final String JSON_ENVIRONMENT = "environment";

	@JsonProperty("id")
	private String id = null;

	@JsonProperty("nodeCandidateType")
	private NodeCandidate.NodeCandidateTypeEnum nodeCandidateType = null;

	@JsonProperty("jobIdForByon")
	private String jobIdForBYON;

	@JsonProperty("jobIdForEdge")
	private String jobIdForEDGE;

	@JsonProperty("price")
	private Double price = null;

	@JsonProperty("cloud")
	private Cloud cloud = null;

	@JsonProperty("location")
	private Location location = null;

	@JsonProperty("hardware")
	private Hardware hardware = null;

	@JsonProperty("pricePerInvocation")
	private Double pricePerInvocation = null;

	@JsonProperty("memoryPrice")
	private Double memoryPrice = null;

	@JsonProperty("nodeId")
	private String nodeId = null;

	public String getId() {
		return this.id;
	}

	public NodeCandidate.NodeCandidateTypeEnum getNodeCandidateType() {
		return this.nodeCandidateType;
	}

	public String getJobIdForBYON() {
		return this.jobIdForBYON;
	}

	public String getJobIdForEDGE() {
		return this.jobIdForEDGE;
	}

	public Double getPrice() {
		return this.price;
	}

	public Cloud getCloud() {
		return this.cloud;
	}

	public Location getLocation() {
		return this.location;
	}

	public Hardware getHardware() {
		return this.hardware;
	}

	public Double getPricePerInvocation() {
		return this.pricePerInvocation;
	}

	public Double getMemoryPrice() {
		return this.memoryPrice;
	}

	public String getNodeId() {
		return this.nodeId;
	}

	public NodeCandidate setId(String id) {
		this.id = id;
		return this;
	}

	public NodeCandidate setNodeCandidateType(NodeCandidate.NodeCandidateTypeEnum nodeCandidateType) {
		this.nodeCandidateType = nodeCandidateType;
		return this;
	}

	public NodeCandidate setJobIdForBYON(String jobIdForBYON) {
		this.jobIdForBYON = jobIdForBYON;
		return this;
	}

	public NodeCandidate setJobIdForEDGE(String jobIdForEDGE) {
		this.jobIdForEDGE = jobIdForEDGE;
		return this;
	}

	public NodeCandidate setPrice(Double price) {
		this.price = price;
		return this;
	}

	public NodeCandidate setCloud(Cloud cloud) {
		this.cloud = cloud;
		return this;
	}

	public NodeCandidate setLocation(Location location) {
		this.location = location;
		return this;
	}

	public NodeCandidate setHardware(Hardware hardware) {
		this.hardware = hardware;
		return this;
	}

	public NodeCandidate setPricePerInvocation(Double pricePerInvocation) {
		this.pricePerInvocation = pricePerInvocation;
		return this;
	}

	public NodeCandidate setMemoryPrice(Double memoryPrice) {
		this.memoryPrice = memoryPrice;
		return this;
	}

	public NodeCandidate setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	public boolean isEdgeNodeCandidate() {
		return this.nodeCandidateType.equals(NodeCandidate.NodeCandidateTypeEnum.EDGE);
	}

	public static enum NodeCandidateTypeEnum {
		IAAS("IAAS"), FAAS("FAAS"), PAAS("PAAS"), BYON("BYON"), EDGE("EDGE"), SIMULATION("SIMULATION");

		private final String value;

		private NodeCandidateTypeEnum(String value) {
			this.value = value;
		}

		@JsonValue
		public String toString() {
			return String.valueOf(this.value);
		}

		@JsonCreator
		public static NodeCandidate.NodeCandidateTypeEnum fromValue(String text) {
			NodeCandidate.NodeCandidateTypeEnum[] var1 = values();
			int var2 = var1.length;

			for (int var3 = 0; var3 < var2; ++var3) {
				NodeCandidate.NodeCandidateTypeEnum b = var1[var3];
				if (String.valueOf(b.value).equals(text.toUpperCase(Locale.ROOT))) {
					return b;
				}
			}

			return null;
		}
	}

	@Override
	public String toString() {
		return "NodeCandidate{" +
				"id='" + id + '\'' +
				", nodeCandidateType='" + nodeCandidateType + '\'' +
				", jobIdForBYON='" + jobIdForBYON + '\'' +
				", jobIdForEDGE='" + jobIdForEDGE + '\'' +
				", price=" + price +
				", cloud=" + cloud +
				", location=" + location +
				", hardware=" + hardware +
				", pricePerInvocation=" + pricePerInvocation +
				", memoryPrice=" + memoryPrice +
				", nodeId='" + nodeId + '\'' +
				'}';
	}
}