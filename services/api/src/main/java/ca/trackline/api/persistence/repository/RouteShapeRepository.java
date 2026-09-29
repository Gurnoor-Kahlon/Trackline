package ca.trackline.api.persistence.repository;

import ca.trackline.api.persistence.entity.RouteShapeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Route geometry.
 */
@Repository
public interface RouteShapeRepository extends JpaRepository<RouteShapeEntity, String> {
}
