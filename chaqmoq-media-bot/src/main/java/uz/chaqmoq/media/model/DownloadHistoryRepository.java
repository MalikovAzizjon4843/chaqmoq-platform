package uz.chaqmoq.media.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DownloadHistoryRepository extends JpaRepository<DownloadHistory, Long> {

    @Query("SELECT COUNT(d) FROM DownloadHistory d WHERE d.success = true")
    long countSuccessful();

    @Query("SELECT d.platform, COUNT(d) FROM DownloadHistory d GROUP BY d.platform")
    List<Object[]> countByPlatform();

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);
    
    long countBySuccessTrue();
    
    long countBySuccessFalse();
}
