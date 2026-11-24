package SDD.smash.Dwelling.Batch.Runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@ConditionalOnProperty(name = {"app.batch.enabled", "dwelling.refresh.enabled"},
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class DwellingBatchRunner {
    private static final String JOB_NAME = "dwellingJob";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int HISTORY_PAGE_SIZE = 50;

    private final Job dwellingJob;
    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final Clock clock;
    private boolean applicationReady;

    @Autowired
    public DwellingBatchRunner(@Qualifier("dwellingJob") Job dwellingJob,
                               JobLauncher jobLauncher, JobExplorer jobExplorer) {
        this(dwellingJob, jobLauncher, jobExplorer, Clock.system(SEOUL));
    }

    DwellingBatchRunner(Job dwellingJob, JobLauncher jobLauncher,
                        JobExplorer jobExplorer, Clock clock) {
        this.dwellingJob = dwellingJob;
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.clock = clock;
    }

    @Order(9)
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void runOnceAfterStartup() {
        // Address seed jobs must finish before dwelling collection.
        applicationReady = true;
        refreshIfNeeded();
    }

    @Scheduled(cron = "${dwelling.refresh.cron:0 0 3 1 * *}", zone = "Asia/Seoul")
    public synchronized void refreshOnSchedule() {
        if (applicationReady) {
            refreshIfNeeded();
        }
    }

    private void refreshIfNeeded() {
        YearMonth refreshMonth = YearMonth.from(ZonedDateTime.ofInstant(clock.instant(), SEOUL));
        try {
            if (!jobExplorer.findRunningJobExecutions(JOB_NAME).isEmpty()) {
                log.info("Skip dwelling refresh: a dwelling execution is already running");
                return;
            }
            if (completedIn(refreshMonth)) {
                log.info("Skip dwelling refresh: completed in {}", refreshMonth);
                return;
            }

            JobParameters parameters = new JobParametersBuilder()
                    .addString("refreshMonth", refreshMonth.format(MONTH_FORMAT))
                    .addString("dealYmd", refreshMonth.minusMonths(1).format(MONTH_FORMAT))
                    .addLong("months", 12L)
                    .toJobParameters();

            // Stable parameters reject concurrent duplicate launches and let FAILED jobs restart.
            JobExecution execution = jobLauncher.run(dwellingJob, parameters);
            log.info("Dwelling refresh: month={}, executionId={}, status={}",
                    refreshMonth, execution.getId(), execution.getStatus());
        } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException e) {
            log.info("Skip dwelling refresh: month={} is already running or complete", refreshMonth);
        } catch (Exception e) {
            log.error("Failed to refresh dwelling data for {}", refreshMonth, e);
        }
    }

    private boolean completedIn(YearMonth month) {
        for (int offset = 0; ; offset += HISTORY_PAGE_SIZE) {
            List<JobInstance> instances = jobExplorer.getJobInstances(JOB_NAME, offset, HISTORY_PAGE_SIZE);
            for (JobInstance instance : instances) {
                for (JobExecution execution : jobExplorer.getJobExecutions(instance)) {
                    if (execution.getStatus() != BatchStatus.COMPLETED || execution.getEndTime() == null) {
                        continue;
                    }
                    // Batch stores LocalDateTime in the JVM timezone (production: Asia/Seoul).
                    YearMonth completedMonth = YearMonth.from(execution.getEndTime()
                            .atZone(ZoneId.systemDefault()).withZoneSameInstant(SEOUL));
                    if (completedMonth.equals(month)) {
                        return true;
                    }
                }
            }
            if (instances.size() < HISTORY_PAGE_SIZE) {
                return false;
            }
        }
    }
}
