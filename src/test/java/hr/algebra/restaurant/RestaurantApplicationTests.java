package hr.algebra.restaurant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RestaurantApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void startsAgainstRealPostgres() {
		String version = jdbc.queryForObject("select version()", String.class);

		assertThat(version).startsWith("PostgreSQL 18");
	}

}
