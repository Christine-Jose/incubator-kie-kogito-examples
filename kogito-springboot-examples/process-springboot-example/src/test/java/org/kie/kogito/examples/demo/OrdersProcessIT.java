/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kie.kogito.examples.demo;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kie.kogito.Model;
import org.kie.kogito.auth.IdentityProvider;
import org.kie.kogito.auth.IdentityProviders;
import org.kie.kogito.auth.SecurityPolicy;
import org.kie.kogito.examples.DemoApplication;
import org.kie.kogito.process.Process;
import org.kie.kogito.process.ProcessInstance;
import org.kie.kogito.process.ProcessInstances;
import org.kie.kogito.process.WorkItem;
import org.kie.kogito.testcontainers.springboot.InfinispanSpringBootTestResource;
import org.kie.kogito.testcontainers.springboot.KafkaSpringBootTestResource;
import org.kie.kogito.usertask.UserTaskInstance;
import org.kie.kogito.usertask.UserTasks;
import org.kie.kogito.usertask.impl.lifecycle.DefaultUserTaskLifeCycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.kie.kogito.test.utils.ProcessInstancesTestUtils.assertEmpty;
import static org.kie.kogito.test.utils.ProcessInstancesTestUtils.getFirst;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = DemoApplication.class)
@ContextConfiguration(initializers = { InfinispanSpringBootTestResource.Conditional.class, KafkaSpringBootTestResource.Conditional.class })
public class OrdersProcessIT {

    @Autowired
    @Qualifier("demo.orders")
    Process<? extends Model> orderProcess;

    @Autowired
    @Qualifier("demo.orderItems")
    Process<? extends Model> orderItemsProcess;

    @Autowired
    UserTasks userTasks;

    private SecurityPolicy policy = SecurityPolicy.of(IdentityProviders.of("john", Collections.singletonList("managers")));

    @Test
    public void testOrderProcess() {
        assertNotNull(orderProcess);

        Model m = orderProcess.createModel();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("approver", "john");
        parameters.put("order", new Order("12345", false, 0.0));
        m.fromMap(parameters);

        ProcessInstance<?> processInstance = orderProcess.createInstance(m);
        processInstance.start();

        assertEquals(ProcessInstance.STATE_ACTIVE, processInstance.status());
        Model result = (Model) processInstance.variables();
        assertEquals(2, result.toMap().size());
        assertTrue(((Order) result.toMap().get("order")).getTotal() > 0);

        IdentityProvider johnUser = IdentityProviders.of("john", Collections.singletonList("managers"));
        List<UserTaskInstance> userTaskInstances = userTasks.instances().findByIdentity(johnUser);
        userTaskInstances.forEach(ut -> {
            ut.transition(DefaultUserTaskLifeCycle.COMPLETE, Collections.emptyMap(), johnUser);
        });

        Optional<?> pi = orderProcess.instances().findById(processInstance.id());
        assertFalse(pi.isPresent());

        assertEmpty(orderProcess.instances());
        assertEmpty(orderItemsProcess.instances());
    }

    @Test
    public void testOrderProcessWithError() {
        assertNotNull(orderProcess);

        Model m = orderProcess.createModel();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("order", new Order("12345", false, 0.0));
        m.fromMap(parameters);

        ProcessInstance<?> processInstance = orderProcess.createInstance(m);
        processInstance.start();

        assertEquals(ProcessInstance.STATE_ERROR, processInstance.status());
        assertTrue(processInstance.error().isPresent());

        parameters = new HashMap<>();
        parameters.put("approver", "john");
        parameters.put("order", new Order("12345", false, 0.0));
        m.fromMap(parameters);
        ((ProcessInstance) processInstance).updateVariables(m);

        processInstance.error().get().retrigger();
        assertEquals(ProcessInstance.STATE_ACTIVE, processInstance.status());

        Model result = (Model) processInstance.variables();
        assertEquals(2, result.toMap().size());
        assertTrue(((Order) result.toMap().get("order")).getTotal() > 0);

        ProcessInstances<? extends Model> orderItemProcesses = orderItemsProcess.instances();

        ProcessInstance<?> childProcessInstance = getFirst(orderItemProcesses);

        List<WorkItem> workItems = childProcessInstance.workItems(policy);
        assertEquals(1, workItems.size());

        IdentityProvider johnUser = IdentityProviders.of("john", Collections.singletonList("managers"));
        List<UserTaskInstance> userTaskInstances = userTasks.instances().findByIdentity(johnUser);
        userTaskInstances.forEach(ut -> {
            ut.transition(DefaultUserTaskLifeCycle.COMPLETE, Collections.emptyMap(), johnUser);
        });

        assertEmpty(orderProcess.instances());
        assertEmpty(orderItemsProcess.instances());
    }
}
