package com.yaytsa.server.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCleaner {

  @Autowired private JdbcTemplate jdbc;

  public void clean() {
    jdbc.execute(
        """
        DO $$ DECLARE r RECORD;
        BEGIN
          FOR r IN (
            SELECT tablename FROM pg_tables
            WHERE schemaname = 'public'
              AND tablename != 'flyway_schema_history'
          ) LOOP
            EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' CASCADE';
          END LOOP;
        END $$
        """);
  }
}
