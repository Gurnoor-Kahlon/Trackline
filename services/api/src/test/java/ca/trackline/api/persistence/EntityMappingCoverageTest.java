package ca.trackline.api.persistence;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import ca.trackline.api.testsupport.PostgisIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps migrations and entities from drifting apart.
 *
 * <p>Hibernate's {@code validate} catches a column that an entity declares but the
 * schema lacks. It cannot catch the opposite: a table added by a migration with no
 * entity behind it, which is how a schema quietly grows parts the application
 * cannot read. This compares both directions explicitly.
 */
class EntityMappingCoverageTest extends PostgisIntegrationTest {

    /**
     * Tables that intentionally have no entity.
     *
     * <p>PostGIS and Flyway own the first two. The other two belong to the import:
     * {@code shape_points} is staging written by COPY and read by the geometry
     * derivation, and {@code route_scheduled_headways} is a derived lookup written
     * in bulk and read by aggregate queries. Both are SQL all the way through, so
     * an entity would be unused code rather than a mapping anyone uses.
     */
    private static final Set<String> TABLES_WITHOUT_ENTITIES = Set.of(
            "spatial_ref_sys", "flyway_schema_history", "shape_points", "route_scheduled_headways");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyTableHasAnEntityAndEveryEntityHasATable() {
        Set<String> mappedTables = entityManager.getMetamodel().getEntities().stream()
                .map(this::tableNameOf)
                .collect(Collectors.toSet());

        List<String> schemaTables = jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """,
                String.class);

        Set<String> expectedEntityTables = schemaTables.stream()
                .filter(table -> !TABLES_WITHOUT_ENTITIES.contains(table))
                .collect(Collectors.toSet());

        assertThat(mappedTables)
                .as("a migration added a table with no entity, or an entity was removed")
                .containsExactlyInAnyOrderElementsOf(expectedEntityTables);
    }

    private String tableNameOf(EntityType<?> entityType) {
        jakarta.persistence.Table table = entityType.getJavaType().getAnnotation(jakarta.persistence.Table.class);
        return table == null ? entityType.getName() : table.name();
    }
}
