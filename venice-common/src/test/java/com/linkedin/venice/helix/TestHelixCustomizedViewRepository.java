package com.linkedin.venice.helix;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.ZkServerWrapper;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.PartitionAssignment;
import com.linkedin.venice.meta.RoutingDataRepository;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.pushmonitor.ReadOnlyPartitionStatus;
import com.linkedin.venice.routerapi.ReplicaState;
import com.linkedin.venice.utils.MockTestStateModel;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.controller.HelixControllerMain;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixManager;
import org.apache.helix.model.CustomizedStateConfig;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


/**
 * Test case for HelixCustomizedViewRepository.
 */
public class TestHelixCustomizedViewRepository {
  // Test behavior configuration
  private static final int WAIT_TIME = 1000; // FIXME: Non-deterministic. Will lead to flaky tests.

  private SafeHelixManager manager0, manager1;
  private SafeHelixManager controller;
  private HelixAdmin admin;
  private String clusterName = "UnitTestCLuster";
  private String resourceName = "UnitTest";
  private String zkAddress;
  private int httpPort0, httpPort1;
  private int adminPort;
  private int partitionId0, partitionId1;
  private ZkServerWrapper zkServerWrapper;
  private HelixCustomizedViewRepository repository;
  private SafeHelixManager readManager;
  private HelixPartitionPushStatusAccessor accessor0, accessor1;

  @BeforeMethod(alwaysRun = true)
  public void HelixSetup() throws Exception {
    zkServerWrapper = ServiceFactory.getZkServer();
    zkAddress = zkServerWrapper.getAddress();
    admin = new ZKHelixAdmin(zkAddress);
    admin.addCluster(clusterName);
    HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).
        forCluster(clusterName).build();
    Map<String, String> helixClusterProperties = new HashMap<String, String>();
    helixClusterProperties.put(ZKHelixManager.ALLOW_PARTICIPANT_AUTO_JOIN, String.valueOf(true));
    admin.setConfig(configScope, helixClusterProperties);
    admin.addStateModelDef(clusterName, MockTestStateModel.UNIT_TEST_STATE_MODEL, MockTestStateModel.getDefinition());

    admin.addResource(clusterName, resourceName, 2, MockTestStateModel.UNIT_TEST_STATE_MODEL,
        IdealState.RebalanceMode.FULL_AUTO.toString());
    admin.rebalance(clusterName, resourceName, 2);

    // Build customized state config and update to Zookeeper
    CustomizedStateConfig.Builder customizedStateConfigBuilder = new CustomizedStateConfig.Builder();
    List<String> aggregationEnabledTypes = new ArrayList<String>();
    aggregationEnabledTypes.add(HelixPartitionState.OFFLINE_PUSH.name());
    customizedStateConfigBuilder.setAggregationEnabledTypes(aggregationEnabledTypes);
    CustomizedStateConfig customizedStateConfig = customizedStateConfigBuilder.build();
    admin.addCustomizedStateConfig(clusterName, customizedStateConfig);

    partitionId0 = 0;
    partitionId1 = 1;
    httpPort0 = 50000 + (int) (System.currentTimeMillis() % 10000);
    httpPort1 = 50000 + (int) (System.currentTimeMillis() % 10000) + 1;
    adminPort = 50000 + (int) (System.currentTimeMillis() % 10000) + 2;

    controller = new SafeHelixManager(
        HelixControllerMain.startHelixController(zkAddress, clusterName, Utils.getHelixNodeIdentifier(adminPort),
            HelixControllerMain.STANDALONE));

    manager0 = TestUtils.getParticipant(clusterName, Utils.getHelixNodeIdentifier(httpPort0), zkAddress, httpPort0,
        MockTestStateModel.UNIT_TEST_STATE_MODEL);
    manager0.connect();
    Thread.sleep(WAIT_TIME);
    accessor0 = new HelixPartitionPushStatusAccessor(manager0.getOriginalManager(), manager0.getInstanceName());

    manager1 = TestUtils.getParticipant(clusterName, Utils.getHelixNodeIdentifier(httpPort1), zkAddress, httpPort1,
        MockTestStateModel.UNIT_TEST_STATE_MODEL);
    manager1.connect();
    Thread.sleep(WAIT_TIME);
    accessor1 = new HelixPartitionPushStatusAccessor(manager1.getOriginalManager(), manager1.getInstanceName());

    readManager = new SafeHelixManager(
        HelixManagerFactory.getZKHelixManager(clusterName, "reader", InstanceType.SPECTATOR, zkAddress));
    readManager.connect();

    repository = new HelixCustomizedViewRepository(readManager);
    repository.refresh();

    // Update customized state for each partition on each instance
    accessor0.updateReplicaStatus(resourceName, partitionId0, ExecutionStatus.COMPLETED);
    accessor0.updateReplicaStatus(resourceName, partitionId1, ExecutionStatus.END_OF_PUSH_RECEIVED);
    accessor1.updateReplicaStatus(resourceName, partitionId0, ExecutionStatus.END_OF_PUSH_RECEIVED);
    accessor1.updateReplicaStatus(resourceName, partitionId1, ExecutionStatus.COMPLETED);

    TestUtils.waitForNonDeterministicCompletion(500000, TimeUnit.MILLISECONDS,
        () -> repository.containsKafkaTopic(resourceName)
            && repository.getReplicaStates(resourceName, partitionId0).size() == 2
            && repository.getReplicaStates(resourceName, partitionId0).size() == 2);
  }

  @AfterMethod(alwaysRun = true)
  public void HelixCleanup() {
    manager0.disconnect();
    manager1.disconnect();
    readManager.disconnect();
    controller.disconnect();
    admin.dropCluster(clusterName);
    admin.close();
    zkServerWrapper.close();
  }

  @Test
  public void testGetInstances() throws Exception {
    List<Instance> instances = repository.getReadyToServeInstances(resourceName, partitionId0);
    Assert.assertEquals(1, instances.size());
    Instance instance = instances.get(0);
    Assert.assertEquals(Utils.getHostName(), instance.getHost());
    Assert.assertEquals(httpPort0, instance.getPort());

    instances = repository.getReadyToServeInstances(resourceName, partitionId1);
    Assert.assertEquals(1, instances.size());
    instance = instances.get(0);
    Assert.assertEquals(Utils.getHostName(), instance.getHost());
    Assert.assertEquals(httpPort1, instance.getPort());

    accessor1.updateReplicaStatus(resourceName, partitionId0, ExecutionStatus.COMPLETED);
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      List<Instance> instancesList = repository.getReadyToServeInstances(resourceName, partitionId0);
      Assert.assertEquals(instancesList.size(), 2);
      Assert.assertEquals(new HashSet<>(Arrays.asList(instancesList.get(0).getPort(), instancesList.get(1).getPort())),
          new HashSet<>(Arrays.asList(httpPort0, httpPort1)));
    });

    accessor0.updateReplicaStatus(resourceName, partitionId1, ExecutionStatus.COMPLETED);
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      List<Instance> instancesList = repository.getReadyToServeInstances(resourceName, partitionId1);
      Assert.assertEquals(instancesList.size(), 2);
      Assert.assertEquals(new HashSet<>(Arrays.asList(instancesList.get(0).getPort(), instancesList.get(1).getPort())),
          new HashSet<>(Arrays.asList(httpPort0, httpPort1)));
    });

    // Participant become offline.
    manager0.disconnect();
    // Wait for notification.
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      List<Instance> instancesList = repository.getReadyToServeInstances(resourceName, partitionId0);
      Assert.assertEquals(1, instancesList.size());
      instancesList = repository.getReadyToServeInstances(resourceName, partitionId1);
      Assert.assertEquals(1, instancesList.size());
    });

    // Add a new participant
    int newHttpPort = httpPort0 + 10;
    SafeHelixManager newManager =
        TestUtils.getParticipant(clusterName, Utils.getHelixNodeIdentifier(newHttpPort), zkAddress, newHttpPort,
            MockTestStateModel.UNIT_TEST_STATE_MODEL);
    newManager.connect();
    HelixPartitionPushStatusAccessor newAccessor =
        new HelixPartitionPushStatusAccessor(newManager.getOriginalManager(), newManager.getInstanceName());
    newAccessor.updateReplicaStatus(resourceName, partitionId0, ExecutionStatus.COMPLETED);
    newAccessor.updateReplicaStatus(resourceName, partitionId1, ExecutionStatus.COMPLETED);
    TestUtils.waitForNonDeterministicAssertion(10, TimeUnit.SECONDS, () -> {
      List<Instance> instancesList = repository.getReadyToServeInstances(resourceName, partitionId0);
      Assert.assertEquals(instancesList.size(), 2);
      instancesList = repository.getReadyToServeInstances(resourceName, partitionId1);
      Assert.assertEquals(instancesList.size(), 2);
    });
    newManager.disconnect();
  }

  @Test
  public void testGetReplicaStates() {
    List<ReplicaState> replicaStates = repository.getReplicaStates(resourceName, partitionId0);
    Assert.assertEquals(replicaStates.size(), 2, "Unexpected replication factor");
    for (ReplicaState replicaState : replicaStates) {
      Assert.assertEquals(replicaState.getPartition(), partitionId0, "Unexpected partition number");
      Assert.assertNotNull(replicaState.getParticipantId(), "Participant id should not be null");
      Assert.assertEquals(replicaState.isReadyToServe(),
          replicaState.getVenicePushStatus().equals(ExecutionStatus.COMPLETED.name()));
    }
  }

  @Test
  public void testGetNumberOfPartitions() throws Exception {
    Assert.assertEquals(2, repository.getNumberOfPartitions(resourceName));
    // Participant become offline.
    manager0.disconnect();
    // Wait notification.
    Thread.sleep(WAIT_TIME);
    // Partition will not change
    Assert.assertEquals(2, repository.getNumberOfPartitions(resourceName));
  }

  @Test
  public void testGetNumberOfPartitionsWhenResourceDropped() throws Exception {
    Assert.assertTrue(admin.getResourcesInCluster(clusterName).contains(resourceName));
    // Wait notification.
    Thread.sleep(WAIT_TIME);
    admin.dropResource(clusterName, resourceName);
    accessor0.deleteReplicaStatus(resourceName, partitionId0);
    accessor0.deleteReplicaStatus(resourceName, partitionId1);
    accessor1.deleteReplicaStatus(resourceName, partitionId0);
    accessor1.deleteReplicaStatus(resourceName, partitionId1);
    // Wait notification.
    Thread.sleep(WAIT_TIME);
    Assert.assertFalse(admin.getResourcesInCluster(clusterName).contains(resourceName));
    try {
      // Should not find the resource.
      repository.getNumberOfPartitions(resourceName);
      Assert.fail("Exception should be thrown because resource does not exist now.");
    } catch (VeniceException e) {
      // Expected
    }
  }

  @Test
  public void testGetPartitions() throws Exception {
    PartitionAssignment customizedPartitionAssignment = repository.getPartitionAssignments(resourceName);
    Assert.assertEquals(2, customizedPartitionAssignment.getAssignedNumberOfPartitions());
    Assert.assertEquals(1, customizedPartitionAssignment.getPartition(partitionId0)
        .getInstancesInState(ExecutionStatus.COMPLETED.name())
        .size());
    Assert.assertEquals(0, customizedPartitionAssignment.getPartition(partitionId0).getWorkingInstances().size());

    Instance instance = customizedPartitionAssignment.getPartition(partitionId0)
        .getInstancesInState(ExecutionStatus.COMPLETED.name())
        .get(0);
    Assert.assertEquals(Utils.getHostName(), instance.getHost());
    Assert.assertEquals(httpPort0, instance.getPort());

    // Get assignment from external view
    PartitionAssignment partitionAssignment = repository.getPartitionAssignments(resourceName);
    List<Instance> liveInstances = partitionAssignment.getPartition(partitionId0).getWorkingInstances();
    // customized view does not have working instances
    Assert.assertEquals(0, liveInstances.size());

    // Participant become offline.
    manager0.disconnect();
    // Wait notification.
    Thread.sleep(WAIT_TIME);
    customizedPartitionAssignment = repository.getPartitionAssignments(resourceName);
    Assert.assertEquals(2, customizedPartitionAssignment.getAssignedNumberOfPartitions());
    Assert.assertEquals(0, customizedPartitionAssignment.getPartition(partitionId0)
        .getInstancesInState(ExecutionStatus.COMPLETED.name())
        .size());
  }

  @Test
  public void testListeners() throws Exception {
    final boolean[] isNoticed = {false, false, false, false};
    RoutingDataRepository.RoutingDataChangedListener listener = new RoutingDataRepository.RoutingDataChangedListener() {
      @Override
      public void onExternalViewChange(PartitionAssignment partitionAssignment) {
        isNoticed[0] = true;
      }

      @Override
      public void onCustomizedViewChange(PartitionAssignment partitionAssignment) {
        isNoticed[1] = true;
      }

      @Override
      public void onPartitionStatusChange(String topic, ReadOnlyPartitionStatus partitionStatus) {
        isNoticed[2] = true;
      }

      @Override
      public void onRoutingDataDeleted(String kafkaTopic) {
        isNoticed[3] = true;
      }
    };

    repository.subscribeRoutingDataChange(resourceName, listener);
    // Participant become offline.
    manager0.disconnect();
    // Wait notification.
    Thread.sleep(WAIT_TIME);
    Assert.assertEquals(isNoticed[0], false, "External view change should not be triggered");
    Assert.assertEquals(isNoticed[1], true, "Can not get notification from customized view change.");
    Assert.assertEquals(isNoticed[2], true, "Can not get notification from per partition change.");
    Assert.assertEquals(isNoticed[3], false, "There is not resource deleted.");

    isNoticed[1] = false;
    isNoticed[2] = false;
    repository.unSubscribeRoutingDataChange(resourceName, listener);
    manager0.connect();
    // Wait notification.
    Thread.sleep(WAIT_TIME);
    Assert.assertEquals(isNoticed[0], false, "Should not get notification after un-registering.");
    Assert.assertEquals(isNoticed[1], false, "Should not get notification after un-registering.");
    Assert.assertEquals(isNoticed[2], false, "Should not get notification after un-registering.");
    Assert.assertEquals(isNoticed[3], false, "Should not get notification after un-registering.");

    repository.subscribeRoutingDataChange(resourceName, listener);

    admin.dropResource(clusterName, resourceName);

    accessor0.deleteReplicaStatus(resourceName, partitionId0);
    accessor0.deleteReplicaStatus(resourceName, partitionId1);
    accessor1.deleteReplicaStatus(resourceName, partitionId0);
    accessor1.deleteReplicaStatus(resourceName, partitionId1);
    // Wait notification.
    Thread.sleep(WAIT_TIME);

    Assert.assertEquals(isNoticed[0], false, "Should not get notification after resource is deleted.");
    Assert.assertEquals(isNoticed[1], false, "Should not get notification after resource is deleted.");
    Assert.assertEquals(isNoticed[2], false, "Should not get notification after resource is deleted.");
    Assert.assertEquals(isNoticed[3], true, "There is a resource deleted.");
  }
}