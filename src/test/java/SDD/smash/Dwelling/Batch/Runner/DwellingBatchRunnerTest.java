package SDD.smash.Dwelling.Batch.Runner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DwellingBatchRunnerTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Job job = mock(Job.class);
    private final JobLauncher launcher = mock(JobLauncher.class);
    private final JobExplorer explorer = mock(JobExplorer.class);
    private final Clock clock = mock(Clock.class);
    private DwellingBatchRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-24T00:00:00Z"));
        when(launcher.run(eq(job), any())).thenAnswer(invocation -> {
            JobExecution result = new JobExecution(100L, invocation.getArgument(1));
            result.setStatus(BatchStatus.COMPLETED);
            return result;
        });
        runner = new DwellingBatchRunner(job, launcher, explorer, clock);
    }

    @Test
    void firstStartupUsesPreviousMonthAndTwelveMonthsWithoutSeedVersion() throws Exception {
        runner.runOnceAfterStartup();

        JobParameters parameters = launchedParameters();
        assertThat(parameters.getString("refreshMonth")).isEqualTo("202611");
        assertThat(parameters.getString("dealYmd")).isEqualTo("202610");
        assertThat(parameters.getLong("months")).isEqualTo(12L);
        assertThat(parameters.getParameters()).doesNotContainKeys("seedVersion", "triggerTime");
    }

    @Test
    void skipsLegacySuccessCompletedThisMonthOnStartupAndSchedule() {
        history(BatchStatus.COMPLETED, "2026-11-02T02:00:00+09:00");

        runner.runOnceAfterStartup();
        runner.refreshOnSchedule();

        verifyNoInteractions(launcher);
    }

    @Test
    void previousMonthSuccessDoesNotPreventThisMonthRefresh() throws Exception {
        history(BatchStatus.COMPLETED, "2026-10-31T23:59:59+09:00");

        runner.runOnceAfterStartup();

        assertThat(launchedParameters().getString("refreshMonth")).isEqualTo("202611");
    }

    @Test
    void failedRunThisMonthDoesNotCountAsSuccess() throws Exception {
        history(BatchStatus.FAILED, "2026-11-02T02:00:00+09:00");

        runner.runOnceAfterStartup();

        verify(launcher).run(eq(job), any());
    }

    @Test
    void restartAfterFailureUsesTheSameJobIdentity() throws Exception {
        history(BatchStatus.FAILED, "2026-11-02T02:00:00+09:00");

        runner.runOnceAfterStartup();
        new DwellingBatchRunner(job, launcher, explorer, clock).runOnceAfterStartup();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(launcher, times(2)).run(eq(job), captor.capture());
        assertThat(captor.getAllValues().get(0)).isEqualTo(captor.getAllValues().get(1));
    }

    @Test
    void doesNotLaunchWhileAnotherDwellingExecutionIsRunning() {
        when(explorer.findRunningJobExecutions("dwellingJob"))
                .thenReturn(Set.of(new JobExecution(1L)));

        runner.runOnceAfterStartup();
        runner.refreshOnSchedule();

        verifyNoInteractions(launcher);
    }

    @Test
    void waitsForApplicationReadyBeforeScheduledLaunch() {
        runner.refreshOnSchedule();

        verifyNoInteractions(explorer, launcher);
    }

    @Test
    void scheduledLaunchRefreshesAfterMonthChangesInKorea() throws Exception {
        history(BatchStatus.COMPLETED, "2026-11-24T02:00:00+09:00");
        runner.runOnceAfterStartup();
        // UTC is still November; Korea is already December.
        when(clock.instant()).thenReturn(Instant.parse("2026-11-30T18:00:00Z"));

        runner.refreshOnSchedule();

        JobParameters parameters = launchedParameters();
        assertThat(parameters.getString("refreshMonth")).isEqualTo("202612");
        assertThat(parameters.getString("dealYmd")).isEqualTo("202611");
    }

    @Test
    void januaryRefreshUsesDecemberOfPreviousYear() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-12-31T18:00:00Z"));

        runner.runOnceAfterStartup();

        assertThat(launchedParameters().getString("dealYmd")).isEqualTo("202612");
    }

    @Test
    void successBeyondTheFirstHistoryPageStillPreventsDuplicateRefresh() {
        List<JobInstance> firstPage = LongStream.rangeClosed(1, 50)
                .mapToObj(id -> new JobInstance(id, "dwellingJob")).toList();
        when(explorer.getJobInstances("dwellingJob", 0, 50)).thenReturn(firstPage);
        JobInstance olderInstance = new JobInstance(51L, "dwellingJob");
        when(explorer.getJobInstances("dwellingJob", 50, 50)).thenReturn(List.of(olderInstance));
        when(explorer.getJobExecutions(olderInstance))
                .thenReturn(List.of(execution(BatchStatus.COMPLETED, "2026-11-02T02:00:00+09:00")));

        runner.runOnceAfterStartup();

        verifyNoInteractions(launcher);
    }

    @Test
    void repositoryRejectsConcurrentDuplicateWithoutFailingStartup() throws Exception {
        when(launcher.run(eq(job), any())).thenThrow(new JobExecutionAlreadyRunningException("running"));

        assertThatCode(runner::runOnceAfterStartup).doesNotThrowAnyException();
        verify(launcher).run(eq(job), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.batch.enabled=false", "dwelling.refresh.enabled=false"})
    void disabledRefreshDoesNotRegisterRunner(String property) {
        new ApplicationContextRunner()
                .withPropertyValues(property)
                .withUserConfiguration(DwellingBatchRunner.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DwellingBatchRunner.class);
                });
    }

    @Test
    void scheduleFiresAtThreeAmOnTheFirstDayInKorea() throws Exception {
        Scheduled scheduled = DwellingBatchRunner.class.getMethod("refreshOnSchedule")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        String cron = scheduled.cron();
        String defaultCron = cron.substring(cron.indexOf(':') + 1, cron.length() - 1);
        ZonedDateTime next = CronExpression.parse(defaultCron)
                .next(ZonedDateTime.parse("2026-11-24T09:00:00+09:00[Asia/Seoul]"));
        assertThat(next).isEqualTo(ZonedDateTime.of(2026, 12, 1, 3, 0, 0, 0, SEOUL));
    }

    private JobParameters launchedParameters() throws Exception {
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(launcher).run(eq(job), captor.capture());
        return captor.getValue();
    }

    private void history(BatchStatus status, String completedAt) {
        JobInstance instance = new JobInstance(1L, "dwellingJob");
        when(explorer.getJobInstances("dwellingJob", 0, 50)).thenReturn(List.of(instance));
        when(explorer.getJobExecutions(instance)).thenReturn(List.of(execution(status, completedAt)));
    }

    private JobExecution execution(BatchStatus status, String completedAt) {
        JobExecution execution = new JobExecution(1L);
        execution.setStatus(status);
        execution.setEndTime(LocalDateTime.ofInstant(
                ZonedDateTime.parse(completedAt).toInstant(), ZoneId.systemDefault()));
        return execution;
    }
}
