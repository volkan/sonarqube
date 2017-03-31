/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.queue;

import com.google.common.base.Optional;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Random;
import javax.annotation.Nullable;
import org.assertj.guava.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.TestSystem2;
import org.sonar.ce.monitoring.CEQueueStatus;
import org.sonar.core.util.UuidFactory;
import org.sonar.core.util.UuidFactoryImpl;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.ce.CeActivityDto;
import org.sonar.db.ce.CeQueueDto;
import org.sonar.db.ce.CeTaskTypes;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.server.computation.monitoring.CEQueueStatusImpl;
import org.sonar.server.organization.DefaultOrganization;
import org.sonar.server.organization.DefaultOrganizationProvider;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class InternalCeQueueImplTest {

  private static final String AN_ANALYSIS_UUID = "U1";
  private static final String WORKER_UUID_1 = "worker uuid 1";
  private static final String WORKER_UUID_2 = "worker uuid 2";

  private System2 system2 = new TestSystem2().setNow(1_450_000_000_000L);

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public DbTester dbTester = DbTester.create(system2);

  private DbSession session = dbTester.getSession();

  private UuidFactory uuidFactory = UuidFactoryImpl.INSTANCE;
  private CEQueueStatus queueStatus = new CEQueueStatusImpl(dbTester.getDbClient());
  private DefaultOrganizationProvider defaultOrganizationProvider = mock(DefaultOrganizationProvider.class);
  private InternalCeQueue underTest = new InternalCeQueueImpl(system2, dbTester.getDbClient(), uuidFactory, queueStatus, defaultOrganizationProvider);

  @Before
  public void setUp() throws Exception {
    OrganizationDto defaultOrganization = dbTester.getDefaultOrganization();
    when(defaultOrganizationProvider.get()).thenReturn(DefaultOrganization.newBuilder()
      .setUuid(defaultOrganization.getUuid())
      .setKey(defaultOrganization.getKey())
      .setName(defaultOrganization.getName())
      .setCreatedAt(defaultOrganization.getCreatedAt())
      .setUpdatedAt(defaultOrganization.getUpdatedAt())
      .build());
  }

  @Test
  public void submit_returns_task_populated_from_CeTaskSubmit_and_creates_CeQueue_row() {
    CeTaskSubmit taskSubmit = createTaskSubmit(CeTaskTypes.REPORT, "PROJECT_1", "rob");
    CeTask task = underTest.submit(taskSubmit);

    verifyCeTask(taskSubmit, task, null);
    verifyCeQueueDtoForTaskSubmit(taskSubmit);
  }

  @Test
  public void submit_populates_component_name_and_key_of_CeTask_if_component_exists() {
    ComponentDto componentDto = insertComponent(newComponentDto("PROJECT_1"));
    CeTaskSubmit taskSubmit = createTaskSubmit(CeTaskTypes.REPORT, componentDto.uuid(), null);

    CeTask task = underTest.submit(taskSubmit);

    verifyCeTask(taskSubmit, task, componentDto);
  }

  @Test
  public void submit_returns_task_without_component_info_when_submit_has_none() {
    CeTaskSubmit taskSubmit = createTaskSubmit("not cpt related");

    CeTask task = underTest.submit(taskSubmit);

    verifyCeTask(taskSubmit, task, null);
  }

  @Test
  public void submit_fails_with_ISE_if_paused() {
    underTest.pauseSubmit();

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Compute Engine does not currently accept new tasks");

    submit(CeTaskTypes.REPORT, "PROJECT_1");
  }

  @Test
  public void massSubmit_returns_tasks_for_each_CeTaskSubmit_populated_from_CeTaskSubmit_and_creates_CeQueue_row_for_each() {
    CeTaskSubmit taskSubmit1 = createTaskSubmit(CeTaskTypes.REPORT, "PROJECT_1", "rob");
    CeTaskSubmit taskSubmit2 = createTaskSubmit("some type");

    List<CeTask> tasks = underTest.massSubmit(asList(taskSubmit1, taskSubmit2));

    assertThat(tasks).hasSize(2);
    verifyCeTask(taskSubmit1, tasks.get(0), null);
    verifyCeTask(taskSubmit2, tasks.get(1), null);
    verifyCeQueueDtoForTaskSubmit(taskSubmit1);
    verifyCeQueueDtoForTaskSubmit(taskSubmit2);
  }

  @Test
  public void massSubmit_populates_component_name_and_key_of_CeTask_if_component_exists() {
    ComponentDto componentDto1 = insertComponent(newComponentDto("PROJECT_1"));
    CeTaskSubmit taskSubmit1 = createTaskSubmit(CeTaskTypes.REPORT, componentDto1.uuid(), null);
    CeTaskSubmit taskSubmit2 = createTaskSubmit("something", "non existing component uuid", null);

    List<CeTask> tasks = underTest.massSubmit(asList(taskSubmit1, taskSubmit2));

    assertThat(tasks).hasSize(2);
    verifyCeTask(taskSubmit1, tasks.get(0), componentDto1);
    verifyCeTask(taskSubmit2, tasks.get(1), null);
  }

  @Test
  public void peek_throws_NPE_if_workerUUid_is_null() {
    expectedException.expect(NullPointerException.class);
    expectedException.expectMessage("workerUuid can't be null");

    underTest.peek(null);
  }

  @Test
  public void test_remove() {
    CeTask task = submit(CeTaskTypes.REPORT, "PROJECT_1");
    Optional<CeTask> peek = underTest.peek(WORKER_UUID_1);
    underTest.remove(peek.get(), CeActivityDto.Status.SUCCESS, null, null);

    // queue is empty
    assertThat(dbTester.getDbClient().ceQueueDao().selectByUuid(dbTester.getSession(), task.getUuid()).isPresent()).isFalse();
    assertThat(underTest.peek(WORKER_UUID_2).isPresent()).isFalse();

    // available in history
    Optional<CeActivityDto> history = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), task.getUuid());
    assertThat(history.isPresent()).isTrue();
    assertThat(history.get().getStatus()).isEqualTo(CeActivityDto.Status.SUCCESS);
    assertThat(history.get().getIsLast()).isTrue();
    assertThat(history.get().getAnalysisUuid()).isNull();
  }

  @Test
  public void remove_throws_IAE_if_exception_is_provided_but_status_is_SUCCESS() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Error can be provided only when status is FAILED");

    underTest.remove(mock(CeTask.class), CeActivityDto.Status.SUCCESS, null, new RuntimeException("Some error"));
  }

  @Test
  public void remove_throws_IAE_if_exception_is_provided_but_status_is_CANCELED() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Error can be provided only when status is FAILED");

    underTest.remove(mock(CeTask.class), CeActivityDto.Status.CANCELED, null, new RuntimeException("Some error"));
  }

  @Test
  public void remove_does_not_set_analysisUuid_in_CeActivity_when_CeTaskResult_has_no_analysis_uuid() {
    CeTask task = submit(CeTaskTypes.REPORT, "PROJECT_1");
    Optional<CeTask> peek = underTest.peek(WORKER_UUID_1);
    underTest.remove(peek.get(), CeActivityDto.Status.SUCCESS, newTaskResult(null), null);

    // available in history
    Optional<CeActivityDto> history = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), task.getUuid());
    assertThat(history.isPresent()).isTrue();
    assertThat(history.get().getAnalysisUuid()).isNull();
  }

  @Test
  public void remove_sets_analysisUuid_in_CeActivity_when_CeTaskResult_has_analysis_uuid() {
    CeTask task = submit(CeTaskTypes.REPORT, "PROJECT_1");

    Optional<CeTask> peek = underTest.peek(WORKER_UUID_2);
    underTest.remove(peek.get(), CeActivityDto.Status.SUCCESS, newTaskResult(AN_ANALYSIS_UUID), null);

    // available in history
    Optional<CeActivityDto> history = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), task.getUuid());
    assertThat(history.isPresent()).isTrue();
    assertThat(history.get().getAnalysisUuid()).isEqualTo("U1");
  }

  @Test
  public void remove_saves_error_message_and_stacktrace_when_exception_is_provided() {
    Throwable error = new NullPointerException("Fake NPE to test persistence to DB");

    CeTask task = submit(CeTaskTypes.REPORT, "PROJECT_1");
    Optional<CeTask> peek = underTest.peek(WORKER_UUID_1);
    underTest.remove(peek.get(), CeActivityDto.Status.FAILED, null, error);

    Optional<CeActivityDto> activityDto = dbTester.getDbClient().ceActivityDao().selectByUuid(session, task.getUuid());
    Assertions.assertThat(activityDto).isPresent();

    assertThat(activityDto.get().getErrorMessage()).isEqualTo(error.getMessage());
    assertThat(activityDto.get().getErrorStacktrace()).isEqualToIgnoringWhitespace(stacktraceToString(error));
  }

  @Test
  public void remove_copies_executionCount_and_workerUuid() {
    dbTester.getDbClient().ceQueueDao().insert(session, new CeQueueDto()
      .setUuid("uuid")
      .setTaskType("foo")
      .setStatus(CeQueueDto.Status.PENDING)
      .setWorkerUuid("Dustin")
      .setExecutionCount(2));
    dbTester.commit();

    underTest.remove(new CeTask.Builder()
      .setOrganizationUuid("foo")
      .setUuid("uuid")
      .setType("bar")
      .build(), CeActivityDto.Status.SUCCESS, null, null);

    CeActivityDto dto = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), "uuid").get();
    assertThat(dto.getExecutionCount()).isEqualTo(2);
    assertThat(dto.getWorkerUuid()).isEqualTo("Dustin");
  }

  @Test
  public void fail_to_remove_if_not_in_queue() throws Exception {
    CeTask task = submit(CeTaskTypes.REPORT, "PROJECT_1");
    underTest.remove(task, CeActivityDto.Status.SUCCESS, null, null);

    expectedException.expect(IllegalStateException.class);

    underTest.remove(task, CeActivityDto.Status.SUCCESS, null, null);
  }

  @Test
  public void test_peek() throws Exception {
    CeTask task = submit(CeTaskTypes.REPORT, "PROJECT_1");

    Optional<CeTask> peek = underTest.peek(WORKER_UUID_1);
    assertThat(peek.isPresent()).isTrue();
    assertThat(peek.get().getUuid()).isEqualTo(task.getUuid());
    assertThat(peek.get().getType()).isEqualTo(CeTaskTypes.REPORT);
    assertThat(peek.get().getComponentUuid()).isEqualTo("PROJECT_1");

    // no more pending tasks
    peek = underTest.peek(WORKER_UUID_2);
    assertThat(peek.isPresent()).isFalse();
  }

  @Test
  public void peek_overrides_workerUuid_to_argument() {
    dbTester.getDbClient().ceQueueDao().insert(session, new CeQueueDto()
      .setUuid("uuid")
      .setTaskType("foo")
      .setStatus(CeQueueDto.Status.PENDING)
      .setWorkerUuid("must be overriden"));
    dbTester.commit();

    underTest.peek(WORKER_UUID_1);

    CeQueueDto ceQueueDto = dbTester.getDbClient().ceQueueDao().selectByUuid(session, "uuid").get();
    assertThat(ceQueueDto.getWorkerUuid()).isEqualTo(WORKER_UUID_1);
  }

  @Test
  public void peek_nothing_if_paused() throws Exception {
    submit(CeTaskTypes.REPORT, "PROJECT_1");
    underTest.pausePeek();

    Optional<CeTask> peek = underTest.peek(WORKER_UUID_1);
    assertThat(peek.isPresent()).isFalse();
  }

  @Test
  public void peek_peeks_pending_tasks_with_executionCount_equal_to_0_and_increases_it() {
    dbTester.getDbClient().ceQueueDao().insert(session, new CeQueueDto()
      .setUuid("uuid")
      .setTaskType("foo")
      .setStatus(CeQueueDto.Status.PENDING)
      .setExecutionCount(0));
    dbTester.commit();

    assertThat(underTest.peek(WORKER_UUID_1).get().getUuid()).isEqualTo("uuid");
    assertThat(dbTester.getDbClient().ceQueueDao().selectByUuid(session, "uuid").get().getExecutionCount()).isEqualTo(1);
  }

  @Test
  public void peek_peeks_pending_tasks_with_executionCount_equal_to_1_and_increases_it() {
    dbTester.getDbClient().ceQueueDao().insert(session, new CeQueueDto()
      .setUuid("uuid")
      .setTaskType("foo")
      .setStatus(CeQueueDto.Status.PENDING)
      .setExecutionCount(1));
    dbTester.commit();

    assertThat(underTest.peek(WORKER_UUID_1).get().getUuid()).isEqualTo("uuid");
    assertThat(dbTester.getDbClient().ceQueueDao().selectByUuid(session, "uuid").get().getExecutionCount()).isEqualTo(2);
  }

  @Test
  public void peek_ignores_pending_tasks_with_executionCount_equal_to_2() {
    dbTester.getDbClient().ceQueueDao().insert(session, new CeQueueDto()
      .setUuid("uuid")
      .setTaskType("foo")
      .setStatus(CeQueueDto.Status.PENDING)
      .setExecutionCount(2));
    dbTester.commit();

    assertThat(underTest.peek(WORKER_UUID_1).isPresent()).isFalse();
  }

  @Test
  public void peek_ignores_pending_tasks_with_executionCount_greater_than_2() {
    dbTester.getDbClient().ceQueueDao().insert(session, new CeQueueDto()
      .setUuid("uuid")
      .setTaskType("foo")
      .setStatus(CeQueueDto.Status.PENDING)
      .setExecutionCount(2 + Math.abs(new Random().nextInt(100))));
    dbTester.commit();

    assertThat(underTest.peek(WORKER_UUID_1).isPresent()).isFalse();
  }

  @Test
  public void cancel_pending() throws Exception {
    CeTask task = submit(CeTaskTypes.REPORT, "PROJECT_1");

    // ignore
    boolean canceled = underTest.cancel("UNKNOWN");
    assertThat(canceled).isFalse();

    canceled = underTest.cancel(task.getUuid());
    assertThat(canceled).isTrue();
    Optional<CeActivityDto> activity = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), task.getUuid());
    assertThat(activity.isPresent()).isTrue();
    assertThat(activity.get().getStatus()).isEqualTo(CeActivityDto.Status.CANCELED);
  }

  @Test
  public void cancel_copies_executionCount_and_workerUuid() {
    dbTester.getDbClient().ceQueueDao().insert(session, new CeQueueDto()
      .setUuid("uuid")
      .setTaskType("foo")
      .setStatus(CeQueueDto.Status.PENDING)
      .setWorkerUuid("Dustin")
      .setExecutionCount(2));
    dbTester.commit();

    underTest.cancel("uuid");

    CeActivityDto dto = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), "uuid").get();
    assertThat(dto.getExecutionCount()).isEqualTo(2);
    assertThat(dto.getWorkerUuid()).isEqualTo("Dustin");
  }

  @Test
  public void fail_to_cancel_if_in_progress() throws Exception {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage(startsWith("Task is in progress and can't be canceled"));

    CeTask task = submit(CeTaskTypes.REPORT, "PROJECT_1");
    underTest.peek(WORKER_UUID_2);

    underTest.cancel(task.getUuid());
  }

  @Test
  public void cancelAll_pendings_but_not_in_progress() throws Exception {
    CeTask inProgressTask = submit(CeTaskTypes.REPORT, "PROJECT_1");
    CeTask pendingTask1 = submit(CeTaskTypes.REPORT, "PROJECT_2");
    CeTask pendingTask2 = submit(CeTaskTypes.REPORT, "PROJECT_3");
    underTest.peek(WORKER_UUID_2);

    int canceledCount = underTest.cancelAll();
    assertThat(canceledCount).isEqualTo(2);

    Optional<CeActivityDto> history = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), pendingTask1.getUuid());
    assertThat(history.get().getStatus()).isEqualTo(CeActivityDto.Status.CANCELED);
    history = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), pendingTask2.getUuid());
    assertThat(history.get().getStatus()).isEqualTo(CeActivityDto.Status.CANCELED);
    history = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), inProgressTask.getUuid());
    assertThat(history.isPresent()).isFalse();
  }

  @Test
  public void cancelWornOuts_cancels_pending_tasks_with_executionCount_greater_or_equal_to_2() {
    CeQueueDto u1 = insertCeQueueDto("u1", CeQueueDto.Status.PENDING, 0, "worker1");
    CeQueueDto u2 = insertCeQueueDto("u2", CeQueueDto.Status.PENDING, 1, "worker1");
    CeQueueDto u3 = insertCeQueueDto("u3", CeQueueDto.Status.PENDING, 2, "worker1");
    CeQueueDto u4 = insertCeQueueDto("u4", CeQueueDto.Status.PENDING, 3, "worker1");
    CeQueueDto u5 = insertCeQueueDto("u5", CeQueueDto.Status.IN_PROGRESS, 0, "worker1");
    CeQueueDto u6 = insertCeQueueDto("u6", CeQueueDto.Status.IN_PROGRESS, 1, "worker1");
    CeQueueDto u7 = insertCeQueueDto("u7", CeQueueDto.Status.IN_PROGRESS, 2, "worker1");
    CeQueueDto u8 = insertCeQueueDto("u8", CeQueueDto.Status.IN_PROGRESS, 3, "worker1");

    underTest.cancelWornOuts();

    verifyUnmodifiedByCancelWornOuts(u1);
    verifyUnmodifiedByCancelWornOuts(u2);
    verifyCanceled(u3);
    verifyCanceled(u4);
    verifyUnmodifiedByCancelWornOuts(u5);
    verifyUnmodifiedByCancelWornOuts(u6);
    verifyUnmodifiedByCancelWornOuts(u7);
    verifyUnmodifiedByCancelWornOuts(u8);
  }

  private void verifyUnmodifiedByCancelWornOuts(CeQueueDto original) {
    CeQueueDto dto = dbTester.getDbClient().ceQueueDao().selectByUuid(dbTester.getSession(), original.getUuid()).get();
    assertThat(dto.getStatus()).isEqualTo(original.getStatus());
    assertThat(dto.getExecutionCount()).isEqualTo(original.getExecutionCount());
    assertThat(dto.getCreatedAt()).isEqualTo(original.getCreatedAt());
    assertThat(dto.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
  }

  private void verifyCanceled(CeQueueDto original) {
    Assertions.assertThat(dbTester.getDbClient().ceQueueDao().selectByUuid(dbTester.getSession(), original.getUuid())).isAbsent();
    CeActivityDto dto = dbTester.getDbClient().ceActivityDao().selectByUuid(dbTester.getSession(), original.getUuid()).get();
    assertThat(dto.getStatus()).isEqualTo(CeActivityDto.Status.CANCELED);
    assertThat(dto.getExecutionCount()).isEqualTo(original.getExecutionCount());
    assertThat(dto.getWorkerUuid()).isEqualTo(original.getWorkerUuid());
  }

  private CeQueueDto insertCeQueueDto(String uuid, CeQueueDto.Status status, int executionCount, String workerUuid) {
    CeQueueDto dto = new CeQueueDto()
      .setUuid(uuid)
      .setTaskType("foo")
      .setStatus(status)
      .setExecutionCount(executionCount)
      .setWorkerUuid(workerUuid);
    dbTester.getDbClient().ceQueueDao().insert(dbTester.getSession(), dto);
    dbTester.commit();
    return dto;
  }

  @Test
  public void pause_and_resume_submits() throws Exception {
    assertThat(underTest.isSubmitPaused()).isFalse();
    underTest.pauseSubmit();
    assertThat(underTest.isSubmitPaused()).isTrue();
    underTest.resumeSubmit();
    assertThat(underTest.isSubmitPaused()).isFalse();
  }

  @Test
  public void pause_and_resume_peeks() throws Exception {
    assertThat(underTest.isPeekPaused()).isFalse();
    underTest.pausePeek();
    assertThat(underTest.isPeekPaused()).isTrue();
    underTest.resumePeek();
    assertThat(underTest.isPeekPaused()).isFalse();
  }

  private void verifyCeTask(CeTaskSubmit taskSubmit, CeTask task, @Nullable ComponentDto componentDto) {
    if (componentDto == null) {
      assertThat(task.getOrganizationUuid()).isEqualTo(defaultOrganizationProvider.get().getUuid());
    } else {
      assertThat(task.getOrganizationUuid()).isEqualTo(componentDto.getOrganizationUuid());
    }
    assertThat(task.getUuid()).isEqualTo(taskSubmit.getUuid());
    assertThat(task.getComponentUuid()).isEqualTo(task.getComponentUuid());
    assertThat(task.getType()).isEqualTo(taskSubmit.getType());
    if (componentDto == null) {
      assertThat(task.getComponentKey()).isNull();
      assertThat(task.getComponentName()).isNull();
    } else {
      assertThat(task.getComponentKey()).isEqualTo(componentDto.key());
      assertThat(task.getComponentName()).isEqualTo(componentDto.name());
    }
    assertThat(task.getSubmitterLogin()).isEqualTo(taskSubmit.getSubmitterLogin());
  }

  private void verifyCeQueueDtoForTaskSubmit(CeTaskSubmit taskSubmit) {
    Optional<CeQueueDto> queueDto = dbTester.getDbClient().ceQueueDao().selectByUuid(dbTester.getSession(), taskSubmit.getUuid());
    assertThat(queueDto.isPresent()).isTrue();
    assertThat(queueDto.get().getTaskType()).isEqualTo(taskSubmit.getType());
    assertThat(queueDto.get().getComponentUuid()).isEqualTo(taskSubmit.getComponentUuid());
    assertThat(queueDto.get().getSubmitterLogin()).isEqualTo(taskSubmit.getSubmitterLogin());
    assertThat(queueDto.get().getCreatedAt()).isEqualTo(1_450_000_000_000L);
  }

  private ComponentDto newComponentDto(String uuid) {
    return ComponentTesting.newProjectDto(dbTester.getDefaultOrganization(), uuid).setName("name_" + uuid).setKey("key_" + uuid);
  }

  private CeTask submit(String reportType, String componentUuid) {
    return underTest.submit(createTaskSubmit(reportType, componentUuid, null));
  }

  private CeTaskSubmit createTaskSubmit(String type) {
    return createTaskSubmit(type, null, null);
  }

  private CeTaskSubmit createTaskSubmit(String type, @Nullable String componentUuid, @Nullable String submitterLogin) {
    CeTaskSubmit.Builder submission = underTest.prepareSubmit();
    submission.setType(type);
    submission.setComponentUuid(componentUuid);
    submission.setSubmitterLogin(submitterLogin);
    return submission.build();
  }

  private CeTaskResult newTaskResult(@Nullable String analysisUuid) {
    CeTaskResult taskResult = mock(CeTaskResult.class);
    when(taskResult.getAnalysisUuid()).thenReturn(java.util.Optional.ofNullable(analysisUuid));
    return taskResult;
  }

  private ComponentDto insertComponent(ComponentDto componentDto) {
    dbTester.getDbClient().componentDao().insert(session, componentDto);
    session.commit();
    return componentDto;
  }

  private static String stacktraceToString(Throwable error) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    error.printStackTrace(new PrintStream(out));
    return out.toString();
  }
}