package com.linkedin.venice.controller;

import com.linkedin.venice.controller.kafka.consumer.AdminConsumerService;
import com.linkedin.venice.helix.Replica;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.kafka.TopicManager;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.schema.SchemaEntry;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


public interface Admin {
    // Wrapper to include both overall offline push status and other extra useful info
    class OfflinePushStatusInfo {
        private ExecutionStatus executionStatus;
        private Map<String, String> extraInfo;

        public OfflinePushStatusInfo(ExecutionStatus executionStatus) {
            this(executionStatus, new HashMap<>());
        }
        public OfflinePushStatusInfo(ExecutionStatus executionStatus, Map<String, String> extraInfo) {
            this.executionStatus = executionStatus;
            this.extraInfo = extraInfo;
        }
        public ExecutionStatus getExecutionStatus() {
            return executionStatus;
        }
        public Map<String, String> getExtraInfo() {
            return extraInfo;
        }
    }
    void start(String clusterName);

    boolean isClusterValid(String clusterName);

    void addStore(String clusterName, String storeName, String owner, String principles, String keySchema, String valueSchema);

    public void storeMetadataUpdate(String clusterName, String storeName, VeniceHelixAdmin.StoreMetadataOperation operation);

    Version addVersion(String clusterName, String storeName, int versionNumber, int numberOfPartition,
        int replicationFactor);

    /**
     * Use {@link #incrementVersionIdempotent(String, String, String, int, int)} instead
     */
    @Deprecated
    Version incrementVersion(String clusterName, String storeName, int numberOfPartition, int replicationFactor);

    /**
     * The implementation of this method must take no action and return the same Version object if the same parameters
     * are provided on a subsequent invocation.  The expected use is multiple distributed components of a single push
     * (with a single jobPushId) that each need to query Venice for the Version (and Kafka topic) to write into.  The
     * first task triggers a new Version, all subsequent tasks identify with the same jobPushId, and should be provided
     * with the same Version object.
     */
    Version incrementVersionIdempotent(String clusterName, String storeName, String pushJobId, int numberOfPartions, int replicationFactor);

    String getRealTimeTopic(String clusterName, String storeName);

    int getCurrentVersion(String clusterName, String storeName);

    Version peekNextVersion(String clusterName, String storeName);

    /**
     * Delete all of venice versions in given store(including venice resource, kafka topic, offline pushs and all related
     * resources).
     *
     * @throws com.linkedin.venice.exceptions.VeniceException If the given store was not disabled, an exception would be
     *                                                        thrown to reject deletion request.
     */
    List<Version> deleteAllVersionsInStore(String clusterName, String storeName);

    List<Version> versionsForStore(String clusterName, String storeName);

    List<Store> getAllStores(String clusterName);

    /**
     * Get the statuses of all stores. The store status is decided by the current version. For example, if one partition
     * only have 2 ONLINE replicas in the current version, we say this store is under replicated. Refer to {@link
     * com.linkedin.venice.meta.StoreStatus} for the definition of each status.
     *
     * @return a map which's key is store name and value is store's status.
     */
    Map<String,String> getAllStoreStatuses(String clusterName);

    Store getStore(String clusterName, String storeName);

    boolean hasStore(String clusterName, String storeName);

    SchemaEntry getKeySchema(String clusterName, String storeName);

    Collection<SchemaEntry> getValueSchemas(String clusterName, String storeName);

    int getValueSchemaId(String clusterName, String storeName, String valueSchemaStr);

    SchemaEntry getValueSchema(String clusterName, String storeName, int id);


    //Operations for updating store metadata
    SchemaEntry addValueSchema(String clusterName, String storeName, String valueSchemaStr);

    SchemaEntry addValueSchema(String clusterName, String storeName, String valueSchemaStr, int schemaId);

    void setStoreCurrentVersion(String clusterName, String storeName, int versionNumber);

    void setStoreOwner(String clusterName, String storeName, String owner);

    void setStorePartitionCount(String clusterName, String storeName, int partitionCount);

    /**
     * Disable a store from writing will stop push from going to this store.
     */
    void disableStoreWrite(String clusterName, String storeName);

    /**
     * Enable a store on writing will allow push to go to this store.
     */
    void enableStoreWrite(String clusterName, String storeName);

    /**
     * Disable a store on reading will stop read requests from hitting this store/
     */
    void disableStoreRead(String clusterName, String storeName);

    /**
     * Enable a store on reading will allow read requests hit this store.
     */
    void enableStoreRead(String clusterName, String storeName);


    List<String> getStorageNodes(String clusterName);

    Map<String, String> getStorageNodesStatus(String clusterName);

    void removeStorageNode(String clusterName, String instanceId);

    /**
     * Stop the helix controller for a single cluster.
     * @param clusterName
     */
    void stop(String clusterName);

    /**
     * Stop the entire controller but not only the helix controller for a single cluster.
     */
    void stopVeniceController();

    /**
     * Query the status of the offline push by given kafka topic.
     * TODO We use kafka topic to tracking the status now but in the further we should use jobId instead of kafka
     * TODO topic. Right now each kafka topic only have one offline job. But in the further one kafka topic could be
     * TODO assigned multiple jobs like data migration job etc.
     * @return the status of current offline push for the passed kafka topic
     */
    OfflinePushStatusInfo getOffLinePushStatus(String clusterName, String kafkaTopic);

    Map<String, Long> getOfflinePushProgress(String clusterName, String kafkaTopic);

    /**
     * TODO : Currently bootstrap servers are common per Venice Controller cluster
     * This needs to be configured at per store level or per version level.
     * The Kafka bootstrap servers should also be dynamically sent to the Storage Nodes
     * and only controllers should be aware of them.
     *
     * @return kafka bootstrap servers url, if there are multiple will be comma separated.
     */
    String getKafkaBootstrapServers();

    TopicManager getTopicManager();

    /**
     * Check if this controller itself is the master controller of given cluster or not.
     * @param clusterName
     * @return
     */
    boolean isMasterController(String clusterName);

    /**
    * Calculate how many partitions are needed for the given store and size.
    * @param storeName
    * @param storeSize
    * @return
    */
    int calculateNumberOfPartitions(String clusterName, String storeName, long storeSize);

    int getReplicationFactor(String clusterName, String storeName);

    /** number of datacenters, 1 if in single cluster mode.  Could be more if this is a parent controller */
    default int getDatacenterCount(String clusterName){
       return 1;
    }

    List<Replica> getBootstrapReplicas(String clusterName, String kafkaTopic);
    List<Replica> getErrorReplicas(String clusterName, String kafkaTopic);
    List<Replica> getReplicas(String clusterName, String kafkaTopic);

    List<Replica> getReplicasOfStorageNode(String clusterName, String instanceId);

    /**
     * Is the given instance able to remove out from given cluster. For example, if there is only one online replica
     * alive in this cluster which is hosted on the given instance. This instance should not be removed out of cluster,
     * otherwise Venice will lose data. For detail criteria please refer to {@link InstanceStatusDecider}
     *
     * @param helixNodeId nodeId of helix participant. HOST_PORT.
     */
    boolean isInstanceRemovable(String clusterName, String helixNodeId);

    boolean isInstanceRemovable(String clusterName, String helixNodeId, int minActiveReplicas);

    /**
     * Get instance of master controller. If there is no master controller for the given cluster, throw a
     * VeniceException.
     */
    Instance getMasterController(String clusterName);

    void addInstanceToWhitelist(String clusterName, String helixNodeId);

    void removeInstanceFromWhiteList(String clusterName, String helixNodeId);

    Set<String> getWhitelist(String clusterName);

    void killOfflinePush(String clusterName, String kafkaTopic);

    /**
     * Query and return the current status of the given storage node. The "storage node status" is composed by "status" of all
     * replicas in that storage node. "status" is an integer value of Helix state: <ul> <li>DROPPED=1</li> <li>ERROR=2</li>
     * <li>OFFLINE=3</li> <li>BOOTSTRAP=4</li> <li>ONLINE=5</li> </ul> So this method will return a map, the key is the
     * replica name which is composed by resource name and partitionId, and the value is the "status" of this replica.
     */
    StorageNodeStatus getStorageNodesStatus(String clusterName, String instanceId);

    /**
     * Compare the current storage node status and the given storage node status to check is the current one is "Newer"
     * or "Equal" to the given one. Compare will go through each of replica in this storage node, if all their
     * statuses values were larger or equal than the statuses value in the given storage node status, We say current
     * storage node status is "Newer" or "Equal " to the given one.
     */
    boolean isStorageNodeNewerOrEqualTo(String clusterName, String instanceId, StorageNodeStatus oldServerStatus);

    /**
     * Enable or disable the delayed rebalance for the given cluster. By default, the delayed reblance is enabled/disabled
     * depends on the cluster's configuration. Through this method, SRE/DEV could enable or disable the delayed reblance
     * temporarily or set a different delayed rebalance time temporarily.
     *
     * @param  delayedTime how long the helix will not rebalance after a server is disconnected. If the given value
     *                     equal or smaller than 0, we disable the delayed rebalance.
     */

    void setDelayedRebalanceTime(String clusterName, long delayedTime);

    /**
    * Get the current delayed rebalance time value for the given cluster
    * @param clusterName
    */
    long getDelayedRebalanceTime(String clusterName);

    void setAdminConsumerService(String clusterName, AdminConsumerService service);

    /**
     * The admin consumption task tries to deal with failures to process an admin message by retrying.  If there is a
     * message that cannot be processed for some reason, we will need to forcibly skip that message in order to unblock
     * the task from consuming subsequent messages.
     * @param clusterName
     * @param offset
     */
    void skipAdminMessage(String clusterName, long offset);

    /**
     * Get the id of the last succeed execution in this controller.
     */
    long getLastSucceedExecutionId(String clusterName);


    /**
     * Function used by {@link com.linkedin.venice.controller.kafka.consumer.AdminConsumptionTask} to share
     * exception received during consuming admin messages.
     */
    void setLastException(String clusterName, Exception e);

    /**
    * Function used by {@link VeniceParentHelixAdmin} to pick up the latest exception occurred
    * while consuming admin messages.
    * @return
    */
    Exception getLastException(String clusterName);

    /**
    * Get the tracker used to track the execution of the admin command.
    */
    Optional<AdminCommandExecutionTracker> getAdminCommandExecutionTracker();

    void close();

    public interface StoreMetadataOperation {
        /**
         * define the operation that update a store. Return the store after metadata being updated so that it could
         * be updated by metadataRepository
         */
        Store update(Store store);
    }
}
