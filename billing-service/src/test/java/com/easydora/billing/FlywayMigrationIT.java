package com.easydora.billing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves billing-service's Flyway migrations actually ran against a real
 * Postgres, and that every one of them succeeded -- the gap ADR-0011 left
 * open ("neither products-service nor billing-service has ever run a real
 * *IT integration test exercising its own Flyway migration path").
 * BillingServiceApplicationIT already exercises the same path implicitly
 * (spring.jpa.hibernate.ddl-auto=validate would fail the whole context if
 * Flyway's migrations didn't produce a schema Hibernate's own entity
 * mappings agree with), but this test asserts directly against
 * flyway_schema_history instead of only relying on that side effect. See
 * products-service's identical FlywayMigrationIT.
 */
@SpringBootTest
class FlywayMigrationIT {

	@Autowired
	private DataSource dataSource;

	@Test
	void everyMigrationRanSuccessfully() throws Exception {
		List<String> versions = new ArrayList<>();
		try (Connection conn = dataSource.getConnection();
				Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery(
						"SELECT version, success FROM billing_schema.flyway_schema_history ORDER BY installed_rank")) {
			while (rs.next()) {
				String version = rs.getString("version");
				assertThat(rs.getBoolean("success"))
						.as("migration V%s should have succeeded", version)
						.isTrue();
				versions.add(version);
			}
		}

		assertThat(versions)
				.withFailMessage("expected billing-service's Flyway migrations to have actually run")
				.isNotEmpty();
	}
}
