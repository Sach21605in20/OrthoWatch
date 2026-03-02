package com.orthowatch.repository;

import com.orthowatch.model.DailyResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyResponseRepository extends JpaRepository<DailyResponse, UUID> {
  Optional<DailyResponse> findByEpisodeIdAndDayNumber(UUID episodeId, int dayNumber);

  List<DailyResponse> findByEpisodeIdOrderByDayNumberDesc(UUID episodeId);

  List<DailyResponse> findByCompletionStatusAndCreatedAtBefore(
      String completionStatus, OffsetDateTime cutoff);
}
