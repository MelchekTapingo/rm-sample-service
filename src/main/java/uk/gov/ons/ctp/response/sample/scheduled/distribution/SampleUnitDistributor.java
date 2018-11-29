package uk.gov.ons.ctp.response.sample.scheduled.distribution;

import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.response.sample.config.AppConfig;
import uk.gov.ons.ctp.response.sample.domain.model.CollectionExerciseJob;
import uk.gov.ons.ctp.response.sample.domain.model.SampleSummary;
import uk.gov.ons.ctp.response.sample.domain.repository.CollectionExerciseJobRepository;
import uk.gov.ons.ctp.response.sample.domain.repository.SampleSummaryRepository;
import uk.gov.ons.ctp.response.sample.domain.repository.SampleUnitRepository;
import uk.gov.ons.ctp.response.sample.representation.SampleSummaryDTO.SampleState;
import uk.gov.ons.ctp.response.sample.representation.SampleUnitDTO.SampleUnitState;
import uk.gov.ons.ctp.response.sampleunit.definition.SampleUnit;

/** Distributes SampleUnits to Collex when requested via job. Retries failures until successful */
@Component
public class SampleUnitDistributor {
  private static final Logger log = LoggerFactory.getLogger(SampleUnitDistributor.class);

  private static final String LOCK_PREFIX = "SampleCollexJob-";

  private static final int TRANSACTION_TIMEOUT_SECONDS = 3600;

  private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(50);

  @Autowired private AppConfig appConfig;

  @Autowired private SampleUnitSender sampleUnitSender;

  @Autowired private CollectionExerciseJobRepository collectionExerciseJobRepository;

  @Autowired private SampleUnitRepository sampleUnitRepository;

  @Autowired private SampleSummaryRepository sampleSummaryRepository;

  @Autowired private RedissonClient redissonClient;

  @Autowired private SampleUnitMapper sampleUnitMapper;

  /** Scheduled job for distributing SampleUnits */
  @Scheduled(fixedDelayString = "#{appConfig.sampleUnitDistribution.delayMilliSeconds}")
  @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
  public void distribute() {
    List<CollectionExerciseJob> jobs = collectionExerciseJobRepository.findByJobCompleteIsFalse();

    for (CollectionExerciseJob job : jobs) {
      String uniqueLockName = LOCK_PREFIX + job.getCollectionExerciseJobPK();

      RLock lock = redissonClient.getFairLock(uniqueLockName);

      try {
        // Get a lock. Automatically unlock after a certain amount of time to prevent issues
        // when lock holder crashes or Redis crashes causing permanent lockout
        if (lock.tryLock(appConfig.getDataGrid().getLockTimeToLiveSeconds(), TimeUnit.SECONDS)) {
          try {
            processJob(job);
          } finally {
            // Always unlock the distributed lock
            lock.unlock();
          }
        }
      } catch (InterruptedException e) {
        // Ignored - process stopped while waiting for lock
      }
    }
  }

  private void processJob(CollectionExerciseJob job) {
    SampleSummary sampleSummary = sampleSummaryRepository.findById(job.getSampleSummaryId());

    final AtomicInteger errorCount = new AtomicInteger();

    if (sampleSummary.getState() == SampleState.ACTIVE) {
      List<Callable<Boolean>> callables = new LinkedList<>();

      try (Stream<uk.gov.ons.ctp.response.sample.domain.model.SampleUnit> sampleUnits =
          sampleUnitRepository.findBySampleSummaryFKAndState(
              sampleSummary.getSampleSummaryPK(), SampleUnitState.PERSISTED)) {
        sampleUnits.forEach(
            su -> {
              callables.add(
                  () -> {
                    SampleUnit mappedSampleUnit =
                        sampleUnitMapper.mapSampleUnit(
                            su, job.getCollectionExerciseId().toString());

                    try {
                      sampleUnitSender.sendSampleUnit(mappedSampleUnit, su);
                    } catch (CTPException e) {
                      errorCount.incrementAndGet();
                      log.with("sample_unit_id", mappedSampleUnit.getId())
                          .error("Failed to send a sample unit to queue and update state", e);
                    }

                    return Boolean.TRUE;
                  });
            });
      }

      try {
        EXECUTOR_SERVICE.invokeAll(callables);
      } catch (InterruptedException e) {
        e.printStackTrace(); // TODO: Don't care at the moment... just hacking performance
      }
    }

    if (errorCount.get() == 0) {
      sampleUnitRepository.flush();
      job.setJobComplete(true);
      collectionExerciseJobRepository.saveAndFlush(job);
    }
  }
}
