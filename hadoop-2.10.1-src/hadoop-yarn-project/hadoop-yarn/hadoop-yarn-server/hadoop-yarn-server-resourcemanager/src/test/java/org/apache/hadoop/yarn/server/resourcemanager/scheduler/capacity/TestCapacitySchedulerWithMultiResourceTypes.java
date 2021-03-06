/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import com.google.common.collect.ImmutableMap;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.ResourceTypes;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceInformation;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.security.ContainerTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.MockAM;
import org.apache.hadoop.yarn.server.resourcemanager.MockNM;
import org.apache.hadoop.yarn.server.resourcemanager.MockNodes;
import org.apache.hadoop.yarn.server.resourcemanager.MockRM;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.common.fica.FiCaSchedulerApp;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.AppAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.AppAttemptAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeUpdateSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.SchedulerEvent;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.util.resource.DominantResourceCalculator;
import org.apache.hadoop.yarn.util.resource.ResourceUtils;
import org.apache.hadoop.yarn.util.resource.TestResourceUtils;
import org.junit.Assert;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Test Capacity Scheduler with multiple resource types.
 */
public class TestCapacitySchedulerWithMultiResourceTypes {
  private static String RESOURCE_1 = "res1";
  private final int GB = 1024;

  @Test
  public void testMaximumAllocationRefreshWithMultipleResourceTypes() throws Exception {

    // Initialize resource map
    Map<String, ResourceInformation> riMap = new HashMap<>();

    // Initialize mandatory resources
    ResourceInformation memory = ResourceInformation.newInstance(
        ResourceInformation.MEMORY_MB.getName(),
        ResourceInformation.MEMORY_MB.getUnits(),
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB);
    ResourceInformation vcores = ResourceInformation.newInstance(
        ResourceInformation.VCORES.getName(),
        ResourceInformation.VCORES.getUnits(),
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES);
    riMap.put(ResourceInformation.MEMORY_URI, memory);
    riMap.put(ResourceInformation.VCORES_URI, vcores);
    riMap.put(RESOURCE_1, ResourceInformation.newInstance(RESOURCE_1, "", 0,
        ResourceTypes.COUNTABLE, 0, 3333L));

    ResourceUtils.initializeResourcesFromResourceInformationMap(riMap);

    CapacitySchedulerConfiguration csconf =
        new CapacitySchedulerConfiguration();
    csconf.setMaximumApplicationMasterResourcePerQueuePercent("root", 100.0f);
    csconf.setMaximumAMResourcePercentPerPartition("root", "", 100.0f);
    csconf.setMaximumApplicationMasterResourcePerQueuePercent("root.default",
        100.0f);
    csconf.setMaximumAMResourcePercentPerPartition("root.default", "", 100.0f);
    csconf.setResourceComparator(DominantResourceCalculator.class);
    csconf.set(YarnConfiguration.RESOURCE_TYPES, RESOURCE_1);
    csconf.setInt(YarnConfiguration.RESOURCE_TYPES + "." + RESOURCE_1
        + ".maximum-allocation", 3333);

    YarnConfiguration conf = new YarnConfiguration(csconf);
    // Don't reset resource types since we have already configured resource
    // types
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);

    MockRM rm = new MockRM(conf);
    rm.start();

    CapacityScheduler cs = (CapacityScheduler) rm.getResourceScheduler();
    Assert.assertEquals(3333L,
        cs.getMaximumResourceCapability().getResourceValue(RESOURCE_1));
    Assert.assertEquals(3333L,
        cs.getMaximumAllocation().getResourceValue(RESOURCE_1));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.VCORES_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.VCORES_URI));

    // Set RES_1 to 3332 (less than 3333) and refresh CS, failures expected.
    csconf.set(YarnConfiguration.RESOURCE_TYPES, RESOURCE_1);
    csconf.setInt(YarnConfiguration.RESOURCE_TYPES + "." + RESOURCE_1
        + ".maximum-allocation", 3332);

    boolean exception = false;
    try {
      cs.reinitialize(csconf, rm.getRMContext());
    } catch (IOException e) {
      exception = true;
    }

    Assert.assertTrue("Should have exception in CS", exception);

    // Maximum allocation won't be updated
    Assert.assertEquals(3333L,
        cs.getMaximumResourceCapability().getResourceValue(RESOURCE_1));
    Assert.assertEquals(3333L,
        cs.getMaximumAllocation().getResourceValue(RESOURCE_1));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.VCORES_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.VCORES_URI));

    // Set RES_1 to 3334 and refresh CS, should success
    csconf.set(YarnConfiguration.RESOURCE_TYPES, RESOURCE_1);
    csconf.setInt(YarnConfiguration.RESOURCE_TYPES + "." + RESOURCE_1
        + ".maximum-allocation", 3334);
    cs.reinitialize(csconf, rm.getRMContext());

    // Maximum allocation will be updated
    Assert.assertEquals(3334,
        cs.getMaximumResourceCapability().getResourceValue(RESOURCE_1));

    // Since we haven't updated the real configuration of ResourceUtils,
    // cs.getMaximumAllocation won't be updated.
    Assert.assertEquals(3333,
        cs.getMaximumAllocation().getResourceValue(RESOURCE_1));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.VCORES_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.VCORES_URI));

    rm.close();
  }

  @Test(timeout = 300000)
  public void testConsumeAllExtendedResourcesWithSmallMinUserLimitPct()
      throws Exception {
    int GB = 1024;

    // Initialize resource map for 3 types.
    Map<String, ResourceInformation> riMap = new HashMap<>();

    // Initialize mandatory resources
    ResourceInformation memory = ResourceInformation.newInstance(
        ResourceInformation.MEMORY_MB.getName(),
        ResourceInformation.MEMORY_MB.getUnits(),
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB);
    ResourceInformation vcores = ResourceInformation.newInstance(
        ResourceInformation.VCORES.getName(),
        ResourceInformation.VCORES.getUnits(),
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES,
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES);
    ResourceInformation res1 = ResourceInformation.newInstance("res_1",
        "", 0, 10);
    riMap.put(ResourceInformation.MEMORY_URI, memory);
    riMap.put(ResourceInformation.VCORES_URI, vcores);
    riMap.put("res_1", res1);

    ResourceUtils.initializeResourcesFromResourceInformationMap(riMap);

    CapacitySchedulerConfiguration csconf =
        new CapacitySchedulerConfiguration();
    csconf.set("yarn.resource-types", "res_1");
    csconf.set("yarn.resource-types.res_1.minimum-allocation", "0");
    csconf.set("yarn.resource-types.res_1.maximum-allocation", "10");
    csconf.setResourceComparator(DominantResourceCalculator.class);

    YarnConfiguration yarnConf = new YarnConfiguration(csconf);
    // Don't reset resource types since we have already configured resource
    // types
    yarnConf.setBoolean(TestResourceUtils.TEST_CONF_RESET_RESOURCE_TYPES,
        false);
    yarnConf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);

    MockRM rm = new MockRM(yarnConf);
    rm.start();

    CapacityScheduler cs = (CapacityScheduler) rm.getResourceScheduler();
    LeafQueue qb = (LeafQueue)cs.getQueue("default");
    // Setting minimum user limit percent should not affect max user resource
    // limit using extended resources with DRF (see YARN-10009).
    qb.setUserLimit(25);

    // add app 1
    ApplicationId appId = BuilderUtils.newApplicationId(100, 1);
    ApplicationAttemptId appAttemptId =
        BuilderUtils.newApplicationAttemptId(appId, 1);

    RMAppAttemptMetrics attemptMetric =
        new RMAppAttemptMetrics(appAttemptId, rm.getRMContext());
    RMAppImpl app = mock(RMAppImpl.class);
    when(app.getApplicationId()).thenReturn(appId);
    RMAppAttemptImpl attempt = mock(RMAppAttemptImpl.class);
    Container container = mock(Container.class);
    when(attempt.getMasterContainer()).thenReturn(container);
    ApplicationSubmissionContext submissionContext = mock(
        ApplicationSubmissionContext.class);
    when(attempt.getSubmissionContext()).thenReturn(submissionContext);
    when(attempt.getAppAttemptId()).thenReturn(appAttemptId);
    when(attempt.getRMAppAttemptMetrics()).thenReturn(attemptMetric);
    when(app.getCurrentAppAttempt()).thenReturn(attempt);

    rm.getRMContext().getRMApps().put(appId, app);

    SchedulerEvent addAppEvent =
        new AppAddedSchedulerEvent(appId, "default", "user1");
    cs.handle(addAppEvent);
    SchedulerEvent addAttemptEvent =
        new AppAttemptAddedSchedulerEvent(appAttemptId, false);
    cs.handle(addAttemptEvent);

    // add nodes to cluster. Cluster has 20GB, 20 vcores, 80 res_1s.
    HashMap<String, Long> resMap = new HashMap<String, Long>();
    resMap.put("res_1", 80L);
    Resource newResource = Resource.newInstance(2048 * GB, 100, resMap);
    RMNode node = MockNodes.newNodeInfo(0, newResource, 1, "127.0.0.1");
    cs.handle(new NodeAddedSchedulerEvent(node));

    FiCaSchedulerApp fiCaApp1 =
        cs.getSchedulerApplications().get(app.getApplicationId())
            .getCurrentAppAttempt();

    // allocate 8 containers for app1 with 1GB memory, 1 vcore, 10 res_1s
    for (int i = 0; i < 8; i++) {
      fiCaApp1.updateResourceRequests(Collections.singletonList(
          ResourceRequest.newBuilder()
          .capability(TestUtils.createResource(1 * GB, 1,
              ImmutableMap.of("res_1", 10)))
          .numContainers(1)
          .resourceName("*")
          .build()));
      cs.handle(new NodeUpdateSchedulerEvent(node));
    }
    assertEquals(8*GB, fiCaApp1.getCurrentConsumption().getMemorySize());
    assertEquals(80,
        fiCaApp1.getCurrentConsumption()
        .getResourceInformation("res_1").getValue());

    rm.close();
  }
}
