package ca.trackline.api.persistence.repository;

import ca.trackline.api.persistence.entity.StopEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Static stops and subway platforms.
 */
@Repository
public interface StopRepository extends JpaRepository<StopEntity, String> {
}
