package com.linkedin.venice.meta;

import com.linkedin.venice.exceptions.VeniceException;
import java.util.HashMap;
import java.util.Map;


/**
 * Enums of the policies used to decide how real-time samza data is replicated.
 */
public enum DataReplicationPolicy {
  /**
   * Default value. Samza job per colo pushes to local real-time topic. Brooklin or Leader SNs replicate data to local
   * version topic.
   */
  NON_AGGREGATE(0),
  /**
   * Single Samza job or Samza job per colo pushes to corp-lva1 real-time topic. KMMs replicate data to local real-time
   * topic and Brooklin or Leader SNs replicate data to local version topic. Or under native replication, leader SNs
   * directly replicate data from corp-lva1 real-time to local version topic.
   */
  AGGREGATE(1),
  /**
   * Samza job per colo pushes to local real-time topic. Leader SNs replicate data from all colos real-time topic to
   * local version topic.
   */
  ACTIVE_ACTIVE(2);

  private final int value;

  private static final Map<Integer, DataReplicationPolicy> DATA_REPLICATION_POLICY_MAP = getDataReplicationPolicyMap();

  DataReplicationPolicy(int value) {
    this.value = value;
  }

  public int getValue() {
    return value;
  }

  public static Map<Integer, DataReplicationPolicy> getDataReplicationPolicyMap() {
    final Map<Integer, DataReplicationPolicy> intToTypeMap = new HashMap<>();
    for (DataReplicationPolicy style : DataReplicationPolicy.values()) {
      intToTypeMap.put(style.value, style);
    }

    return intToTypeMap;
  }

  public static DataReplicationPolicy valueOf(int value) {
    final DataReplicationPolicy style = DATA_REPLICATION_POLICY_MAP.get(value);
    if (style == null) {
      throw new VeniceException("Invalid data replication policy: " + value);
    }

    return style;
  }
}