package dev.ioexception.dicom.config.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;

@Slf4j
@Configuration
@EnableJpaRepositories(
		basePackages = "dev.ioexception.dicom.repository.postgresql",
		entityManagerFactoryRef = "postgresEntityManagerFactory",
		transactionManagerRef = "postgresTransactionManager"
)
public class PostgreSqlConfig {

	@Value("${spring.datasource-postgresql.url}")
	private String url;

	@Value("${spring.datasource-postgresql.username}")
	private String username;

	@Value("${spring.datasource-postgresql.password}")
	private String password;

	@Value("${spring.datasource-postgresql.driver-class-name}")
	private String driverClassName;

	@Bean(name = "postgresDataSource")
	public DataSource postgresDataSource() {
		return DataSourceBuilder.create()
				.url(url)
				.username(username)
				.password(password)
				.driverClassName(driverClassName)
				.build();
	}

	@Bean(name = "postgresEntityManagerFactory")
	public LocalContainerEntityManagerFactoryBean postgresEntityManagerFactory(
			@Qualifier("postgresDataSource") DataSource dataSource) {

		LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
		em.setDataSource(dataSource);
		em.setPackagesToScan("dev.ioexception.dicom.entity.postgresql"); // PostgreSQL 엔티티 패키지 경로

		HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
		em.setJpaVendorAdapter(vendorAdapter);

		HashMap<String, Object> properties = new HashMap<>();
		em.setJpaPropertyMap(properties);

		return em;
	}

	@Bean(name = "postgresTransactionManager")
	public PlatformTransactionManager postgresTransactionManager(
			@Qualifier("postgresEntityManagerFactory") LocalContainerEntityManagerFactoryBean entityManagerFactory) {

		return new JpaTransactionManager(entityManagerFactory.getObject());
	}
}
