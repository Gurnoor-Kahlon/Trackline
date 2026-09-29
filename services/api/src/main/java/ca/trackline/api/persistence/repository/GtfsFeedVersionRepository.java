package ca.trackline.api.persistence.repository;

import java.util.Optional;

import ca.trackline.api.persistence.entity.FeedVersionStatus;
import ca.trackline.api.persistence.entity.GtfsFeedVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * GTFS static import history.
 */
@Repository
public interface GtfsFeedVersionRepository extends JpaRepository<GtfsFeedVersionEntity, Long> {

    /** The network currently being served, if an import has completed. */
    Optional<GtfsFeedVersionEntity> findByStatus(FeedVersionStatus status);

    /** Used to skip re-importing an archive Trackline has already loaded. */
    boolean existsBySha256(String sha256);
}
