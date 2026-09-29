package ca.trackline.api.persistence.repository;

import ca.trackline.api.persistence.entity.RouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Static routes.
 */
@Repository
public interface RouteRepository extends JpaRepository<RouteEntity, String> {
}
